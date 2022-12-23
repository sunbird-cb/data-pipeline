package org.sunbird.job.notification.function

import com.fasterxml.jackson.annotation.JsonInclude

import java.util
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.velocity.{Template, VelocityContext}
import org.apache.velocity.app.{Velocity, VelocityEngine}
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessKeyedFunction, Metrics}
import org.sunbird.job.notification.task.NotificationConfig
import org.sunbird.job.notification.domain.{Event, NotificationMessage, NotificationType, NotificationUtil}
import org.sunbird.job.notification.util.datasecurity.OneWayHashing
import org.sunbird.notification.beans.EmailRequest
import org.sunbird.notification.utils.FCMResponse

import java.io.StringWriter
import java.util.Map.Entry
import java.util.Properties

class NotificationFunction(config: NotificationConfig,  @transient var notificationUtil: NotificationUtil = null) extends BaseProcessKeyedFunction[String, Event, String](config) {

    private[this] val logger = LoggerFactory.getLogger(classOf[NotificationFunction])

    private val mapper = new ObjectMapper with ScalaObjectMapper
    private var accountKey: String = null
    private var maxIterations = 0
    private val MAXITERTIONCOUNT = 2
    val ACTOR = "actor"
    val ID = "id"
    val TYPE = "type"
    val EID = "eid"
    val EDATA = "edata"
    val ACTION = "action"
    val REQUEST = "request"
    val NOTIFICATION = "notification"
    val MODE = "mode"
    val DELIVERY_TYPE = "deliveryType"
    val CONFIG = "config"
    val IDS = "ids"
    val OBJECT = "object"
    val ACTION_NAME = "broadcast-topic-notification-all"
    val NOTIFICATIONS = "notifications"
    val RAW_DATA = "rawData"
    val TOPIC = "topic"
    val TEMPLATE = "template"
    val DATA = "data"
    val MID = "mid"
    val SUBJECT = "subject"
    val ITERATION = "iteration"
    val PARAMS = "params"
    val PDATA = "pdata"
    val VER = "ver"

    override def open(parameters: Configuration): Unit = {
        super.open(parameters)
        maxIterations = getMaxIterations
        notificationUtil =  new NotificationUtil(config.mail_server_from_email, config.mail_server_username, config.mail_server_password, config.mail_server_host, config.mail_server_port,
            config.sms_auth_key, config.sms_default_sender, config.fcm_account_key)
        logger.info("NotificationService:initialize: Service config initialized")
    }

    override def close(): Unit = {
        super.close()
    }

    override def metricsList(): scala.List[String] = {
        List(config.successEventCount, config.failedEventCount, config.skippedEventCount, config.totalEventsCount, config.totalEventsCount)
    }

    override def processElement(event: Event,
                                context: KeyedProcessFunction[String, Event, String]#Context, metrics: Metrics): Unit = {
        import scala.collection.JavaConverters._
        println("Certificate data: " + event)
        metrics.incCounter(config.totalEventsCount)
        var requestHash: String = ""
        var isSuccess: Boolean = false
        if (event.edataMap != null && event.edataMap.size > 0) {
            val actionValue: String = event.edataMap.get("action").get.asInstanceOf[String]
            if (ACTION_NAME.equalsIgnoreCase(actionValue)) {
                val requestMap: scala.collection.immutable.Map[String, Object] = event.edataMap.get(REQUEST).get.asInstanceOf[scala.collection.immutable.Map[String, Object]]
                requestHash = OneWayHashing.encryptVal(mapper.writeValueAsString(requestMap))
                /*if (!(requestHash == event.objectMap.get(ID).get.asInstanceOf[String]))
                    logger.info("NotificationService:processMessage: hashValue is not matching - " + requestHash)
                else {*/
                val notificationMap: scala.collection.immutable.HashMap[String, AnyRef] = requestMap.get(NOTIFICATION).get.asInstanceOf[scala.collection.immutable.HashMap[String, AnyRef]]
                val notificationMode : String = notificationMap.get(MODE).get.toString
                logger.info("Notification mode0: "+ NotificationType.email)
                logger.info("Notification mode1: "+ NotificationType.phone)
                logger.info("Notification mode2: "+ NotificationType.device)
                logger.info("Notification mode value: "+ notificationMode)
                if (notificationMode.equalsIgnoreCase(NotificationType.phone.toString)) {
                    logger.info("phone......")
                    isSuccess = sendSmsNotification(notificationMap, event.msgId)
                } else if (notificationMode == NotificationType.email.toString) {
                    logger.info("mail......")
                    isSuccess = sendEmailNotification(notificationMap)
                } else if (notificationMode == NotificationType.device.toString) {
                    logger.info("device......")
                    isSuccess = notifyDevice(notificationMap)
                }
                if (isSuccess) {
                    metrics.incCounter(config.successEventCount)
                    logger.info("Notification sent successfully.")
                } else {
                    logger.info("Notification sent failure")
                    handleFailureMessage(event, context, metrics)
                }
                //}
            }
            else logger.info("NotificationService:processMessage action name is incorrect: " + actionValue + "for message id:" + event.msgId)
        }
        else logger.info("NotificationService:processMessage event data map is either null or empty for message id:" + event.msgId)
    }

    protected def getMaxIterations: Int = {
        maxIterations = config.max_iteration_count_samza_job
        if (maxIterations == 0) maxIterations = MAXITERTIONCOUNT
        maxIterations
    }

    def sendEmailNotification(notificationMap: scala.collection.immutable.HashMap[String, AnyRef]) = {
        import scala.collection.JavaConverters._
        logger.info("NotificationService:sendEmailNotification map: " + notificationMap)
        val emailIds: util.List[String] = notificationMap.get(IDS).get.asInstanceOf[List[String]].asJava
        logger.info("NotificationService:sendEmailNotification emailids: " + emailIds)
        val templateMap: util.Map[String, AnyRef] = notificationMap.get(TEMPLATE).get.asInstanceOf[scala.collection.immutable.Map[String, AnyRef]].asJava
        val template = new util.HashMap[String, Any]()
        templateMap.forEach((i, j) => {
            template.put(i, j)
        })
        val config = notificationMap.get(CONFIG).get.asInstanceOf[scala.collection.immutable.Map[String, AnyRef]].asJava
        val subject = config.get(SUBJECT).asInstanceOf[String]
        val pdata = new util.HashMap[String, Any]()
        pdata.put(VER, "1.0")
        pdata.put(ID, "org.sunbird.platform")
        val context = new util.HashMap[String, Any]()
        context.put(PDATA, pdata)
        var message = new String()
        if (notificationMap.get(TEMPLATE) != null && templateMap.get(DATA) != null) {
            message = getDataMessage(templateMap.get(DATA).toString, templateMap.get(PARAMS).asInstanceOf[util.Map[String, Any]], context)
            template.put(DATA, message)
        } else if (templateMap.get(ID) != null && notificationMap.get(TEMPLATE) != null) {
            val dataString = createNotificationBody(templateMap, context)
            template.put(DATA, dataString)
        }
        val emailText = template.get(DATA).asInstanceOf[String]
        val emailRequest = new EmailRequest(subject, emailIds, null, null, "", emailText, null)
        notificationUtil.sendEmail(emailRequest)
    }

    def getDataMessage(message: String, node: util.Map[String, Any], reqContext: util.HashMap[String, Any]): String = {
        logger.info("Entering getDataMessage")
        val context: VelocityContext = new VelocityContext()
        if (node != null) {
            val itr: util.Iterator[Entry[String, Any]] = node.entrySet().iterator()
            while (itr.hasNext) {
                val entry: Entry[String, Any] = itr.next()
                if (null != entry.getValue()) {
                    context.put(entry.getKey, entry.getValue)
                }
            }
        }
        var writer: StringWriter = null
        try {
            Velocity.init()
            writer = new StringWriter()
            Velocity.evaluate(context, writer, "SimpleVelocity", message)
        } catch {
            case e: Exception => e.printStackTrace()
                logger.error("NotificationRouter:getMessage : Exception occurred with message =" + e.getMessage)
        }
        logger.info("Writer Object " + writer.toString)
        writer.toString
    }

    def createNotificationBody(template: util.Map[String, AnyRef], context: util.HashMap[String, Any]): String = {
        logger.info("Entering createNotificationBody")
        import scala.collection.JavaConverters._
        readVm(template.get(ID).toString, template.get(PARAMS).asInstanceOf[scala.collection.immutable.Map[String, AnyRef]].asJava, context);
    }

    def readVm(templateName: String, node: util.Map[String, AnyRef], reqContext: util.HashMap[String, Any]): String = {
        logger.info("Entering readVm")
        val engine: VelocityEngine = new VelocityEngine()
        val context: VelocityContext = getContextObj(node)
        val props: Properties = new Properties()
        props.setProperty("resource.loader", "class");
        props.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        var writer: StringWriter = null
        var body = new String()
        val TEMPLATE_SUFFIX = ".vm"
        try {
            engine.init(props)
            val templates: Template = engine.getTemplate("templates/" + templateName + TEMPLATE_SUFFIX)
            writer = new StringWriter()
            templates.merge(context, writer)
            body = writer.toString
        } catch {
            case e: Exception => e.printStackTrace()
                logger.error(reqContext + " Failed to load velocity template =" + templateName)
        } finally {
            if (writer != null) {
                try {
                    writer.close()
                } catch {
                    case e: Exception => e.printStackTrace()
                        logger.error("Failed to closed writer object =" + e.getMessage)
                }
            }
        }
        body
    }
    def getContextObj(node: util.Map[String, AnyRef]): VelocityContext = {
        logger.info("Entering getContextObject")
        var context: VelocityContext = null
        if (node != null) {
            context = new VelocityContext(node)
        } else {
            context = new VelocityContext()
        }
        logger.info("context object " + context)
        context
    }
    def sendSmsNotification(notificationMap: scala.collection.immutable.HashMap[String, AnyRef], msgId: String) = {
        import scala.collection.JavaConverters._
        logger.info("NotificationService:sendSmsNotification map: "+ notificationMap)
        val mobileNumbers :util.List[String] = notificationMap.get(IDS).get.asInstanceOf[List[String]].asJava
        logger.info("NotificationService:sendEmailNotification emailids: "+ mobileNumbers)
        if (mobileNumbers != null) {
            val templateMap = notificationMap.get(TEMPLATE).get.asInstanceOf[scala.collection.immutable.Map[String, AnyRef]].asJava
            val smsText = templateMap.get(DATA).asInstanceOf[String]
            notificationUtil.sendSmsNotification(mobileNumbers, smsText)
        }
        else {
            logger.info("mobile numbers not provided for message id:" + msgId)
            true
        }
    }

    @throws[JsonProcessingException]
    private def notifyDevice(notificationMap: scala.collection.immutable.HashMap[String, AnyRef]) = {
        import scala.collection.JavaConverters._
        var topic: String = null
        var response: FCMResponse = null
        val deviceIds = notificationMap.get(IDS).get.asInstanceOf[List[String]].asJava
        val dataMap = new util.HashMap[String, String]
        dataMap.put(RAW_DATA, mapper.writeValueAsString(notificationMap.get(RAW_DATA)))
        logger.info("NotificationService:processMessage: calling send notification ")
        if (deviceIds != null) {
            response = notificationUtil.sendMultiDeviceNotification(deviceIds, dataMap)
        } else {
            val configMap: util.Map[String, AnyRef] = notificationMap.get(CONFIG).asInstanceOf[util.Map[String, AnyRef]]
            topic = configMap.getOrDefault(TOPIC, "").asInstanceOf[String]
            response = notificationUtil.sendTopicNotification(topic, dataMap)
        }
        if (response != null) {
            logger.info("Send device notiifcation response with canonicalId,ErrorMsg,successCount,FailureCount" + response.getCanonical_ids + "," + response.getError + ", " + response.getSuccess + " " + response.getFailure)
            true
        }
        else {
            logger.info("response is improper from fcm:" + response + "for device ids" + deviceIds + "or topic" + topic)
            false
        }
    }

    def generateKafkaFailureEvent(data: Event) (implicit m : Manifest[NotificationMessage]): NotificationMessage = {
        logger.info("NotificationService:generateKafkaFailureEvent data event: " + data.getJson())
        mapper.readValue(data.getJson(), new TypeReference[NotificationMessage]() {})
    }

    private def handleFailureMessage(event: Event, context: KeyedProcessFunction[String, Event, String]#Context, metrics: Metrics): Unit = {
        logger.info("NotificationService:handleFailureMessage started")
        val iteration : Int = event.edataMap.get(ITERATION).get.asInstanceOf[Int]
        if (iteration < maxIterations) {
            val eventMap : java.util.Map[String, Any] = new util.HashMap
            val eDatamap : java.util.Map[String, Any] = new util.HashMap
            eDatamap.put("action", event.action)
            eDatamap.put("iteration", 2)
            eDatamap.put("request", event.requestMap)
            eventMap.put("actor", event.actor)
            eventMap.put("eid", event.eid)
            eventMap.put("mid", event.mid())
            eventMap.put("trace", event.traceMap)
            //eventMap.put("ets", event.ets)
            eventMap.put("edata", eDatamap)
            eventMap.put("context", event.contextMap)
            eventMap.put("object", event.objectMap)
            val strMapper = new ObjectMapper() with ScalaObjectMapper
            strMapper.registerModule(DefaultScalaModule)
            val failedEvent = strMapper.writeValueAsString(eventMap)
            metrics.incCounter(config.failedEventCount)
            context.output(config.notificationFailedOutputTag, failedEvent)
        } else {
            metrics.incCounter(config.skippedEventCount)
        }
    }
}

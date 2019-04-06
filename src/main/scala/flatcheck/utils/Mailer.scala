package flatcheck.utils

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.Types.OfferDetail
import javax.mail.AuthenticationFailedException
import org.apache.commons.mail.{DefaultAuthenticator, HtmlEmail, SimpleEmail}

import scala.util.{Failure, Success, Try}

class Mailer(val options: FlatcheckConfig) extends LazyLogging {
  // Test the credentials
  testCredentials()

  def testCredentials(): Unit = {
    logger.info("Verifying SMTP credentials...")
    val email = new SimpleEmail()
    email.setHostName(options.get("general", "hostname"))
    email.setSmtpPort(options.get("general", "smtpport").toInt)
    email.setAuthenticator(
      new DefaultAuthenticator(
        options.get("general", "address"),
        options.get("general", "emailpassword")
      )
    )
    email.setSSLOnConnect(options.get("general", "sslonconnect").toBoolean)
    Try(email.getMailSession.getTransport().connect()) match {
      case Failure(_: AuthenticationFailedException) =>
        logger.error(s"Authentication has failed, " +
          s"please check the email address and password in the flatcheck.ini file!")
        System.exit(1)
      case Failure(e) => throw e
      case Success(_) => logger.info("SMTP credentials successfully verified!")
    }
  }

  def sendMessage(msg: String, toAddresses: List[String]): Unit = {
    val email = new HtmlEmail()
    email.setHostName(options.get("general", "hostname"))
    email.setSmtpPort(options.get("general", "smtpport").toInt)
    email.setAuthenticator(
      new DefaultAuthenticator(
        options.get("general", "address"),
        options.get("general", "emailpassword")
      )
    )
    email.setSSLOnConnect(options.get("general", "sslonconnect").toBoolean)
    email.setFrom(options.get("general", "address"))
    email.setSubject(options.get("general", "emailsubject"))
    toAddresses.foreach(address => email.addTo(address))
    email.setHtmlMsg(msg)
    email.send()
    logger.info("  Email sent to: " + toAddresses + "!")
  }

  def sendOfferNotification(offers: List[(String, String, OfferDetail)], toAddresses: List[String]): Unit = {
    val linksHtml = offers.zipWithIndex.map{ case ((siteName, link, (_, priceHUF, sizeSM, roomsNum, address, area, _, _, flatCondition, orientation)), idx) =>
      if (idx % 2 == 0) {
        s"""
           |<tr>
           |  <td class="tg-0lax"><a style="color: #126cbb;" href="$link" target="_blank" rel="noopener">$siteName</a></td>
           |  <td class="tg-0lax">$priceHUF</td>
           |  <td class="tg-lqy6">$sizeSM</td>
           |  <td class="tg-lqy6">$roomsNum</td>
           |  <td class="tg-lqy6">$area</td>
           |  <td class="tg-lqy6">$address</td>
           |  <td class="tg-lqy6">$flatCondition</td>
           |  <td class="tg-lqy6">$orientation</td>
           |</tr>
         """.stripMargin
      } else {
        s"""
           |<tr>
           |  <td class="tg-hmp3"><a style="color: #126cbb;" href="$link" target="_blank" rel="noopener">$siteName</a></td>
           |  <td class="tg-hmp3">$priceHUF</td>
           |  <td class="tg-mb3i">$sizeSM</td>
           |  <td class="tg-mb3i">$roomsNum</td>
           |  <td class="tg-mb3i">$area</td>
           |  <td class="tg-mb3i">$address</td>
           |  <td class="tg-mb3i">$flatCondition</td>
           |  <td class="tg-mb3i">$orientation</td>
           |</tr>
         """.stripMargin
      }
    }
    val site = offers.map{ case (s, _, _) => s }.headOption.getOrElse("No site!")
    val body =
      s"""
        |<head>
        |<style type="text/css">
        |.tg  {border-collapse:collapse;border-spacing:0;border-color:#999;}
        |.tg td{font-family:Arial, sans-serif;font-size:14px;padding:10px 5px;border-style:solid;border-width:1px;overflow:hidden;word-break:normal;border-color:#999;color:#444;background-color:#F7FDFA;}
        |.tg th{font-family:Arial, sans-serif;font-size:14px;font-weight:normal;padding:10px 5px;border-style:solid;border-width:1px;overflow:hidden;word-break:normal;border-color:#999;color:#fff;background-color:#26ADE4;}
        |.tg .tg-hmp3{background-color:#D2E4FC;text-align:left;vertical-align:top}
        |.tg .tg-mb3i{background-color:#D2E4FC;text-align:right;vertical-align:top}
        |.tg .tg-lqy6{text-align:right;vertical-align:top}
        |.tg .tg-ddb2{font-family:serif !important;;text-align:center;vertical-align:top}
        |.tg .tg-0lax{text-align:left;vertical-align:top}
        |
        |.hasTooltip {
        |	   text-decoration-line: underline;
        |    text-decoration-style: dotted;
        |}
        |
        |.hasTooltip span {
        |    display: none;
        |    color: #000;
        |    padding: 3px;
        |}
        |
        |.hasTooltip:hover span {
        |    display: block;
        |    position: absolute;
        |    background-color: #D2E4FC;
        |    border: 1px solid #26ADE4;
        |    margin: 2px 10px;
        |}
        |</style>
        |</head>
        |<body>
        |<table class="tg">
        |    <thead>
        |        <tr>
        |            <th class="tg-ddb2 " colspan="8"><span style="font-size: 24px;">${options.safeGet(site, "searchname")}</span></th>
        |        </tr>
        |    </thead>
        |    <tbody>
        |    <tr>
        |            <td class="tg-hmp3">Site</td>
        |            <td class="tg-hmp3">Price</td>
        |            <td class="tg-hmp3">m^2</td>
        |            <td class="tg-hmp3">Number of rooms</td>
        |            <td class="tg-hmp3">Area</td>
        |            <td class="tg-hmp3">Address</td>
        |            <td class="tg-hmp3">Condition</td>
        |            <td class="tg-hmp3">Orientation</td>
        |        </tr>
        ${linksHtml.mkString("\n")}
        |    </tbody>
        |</body>
      """.stripMargin
    sendMessage(body, toAddresses)
  }

}

package flatcheck.utils

import com.typesafe.scalalogging.LazyLogging
import flatcheck.config.FlatcheckConfig
import flatcheck.db.Types.OfferDetail
import javax.mail.AuthenticationFailedException
import org.apache.commons.mail.{DefaultAuthenticator, HtmlEmail, SimpleEmail}

import scala.util.{Failure, Success, Try}

class Mailer(val options: FlatcheckConfig) extends LazyLogging {
  val toAddresses: List[String] = options.get("general", "sendto").split(",").toList
  if (toAddresses.isEmpty) {
    logger.error("Sendto email addresses are not recognised! Update the ini file and restart the application! Exiting...")
    System.exit(1)
  }
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

  def sendMessage(msg: String): Unit = {
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

  def sendOfferNotification(offers: List[(String, String, OfferDetail)]): Unit = {
    val linksHtml = offers.map{ case (site, link, (offerId, priceHUF, sizeSM, roomsNum, address, area, description, floor, flatCondition)) =>
        s"""
           |<tr>
           |<td><a style="color: #126cbb;" href="$link" target="_blank" rel="noopener">$site</a></td>
           |<td>$priceHUF</td>
           |<td>$sizeSM</td>
           |<td>$area</td>
           |<td>$roomsNum</td>
           |<td>$address</td>
           |<td>$flatCondition</td>
           |</tr>
         """.stripMargin
    }
    val body =
      s"""
         |<h2 style="text-align: center;">Flatcheck info</h2>
         |<table style="border-color: #000607;" border="1">
         |<tbody>
         |<tr>
         |<td style="background: #1a54ba none repeat;">Site</td>
         |<td style="background: #1a54ba none repeat;">Price</td>
         |<td style="background: #1a54ba none repeat;">m^2</td>
         |<td style="background: #1a54ba none repeat;">Number of rooms</td>
         |<td style="background: #1a54ba none repeat;">Area</td>
         |<td style="background: #1a54ba none repeat;">Address</td>
         |<td style="background: #1a54ba none repeat;">Condition</td>
         |</tr>
         ${linksHtml.mkString("\n")}
         |</tbody>
         |</table>
       """.stripMargin
    sendMessage(body)
  }

}

package flatcheck.scraper

import com.typesafe.scalalogging.LazyLogging

class DeepScraper(val name : String) extends Thread(name) with LazyLogging{
  override def run() {
    Thread.sleep(5000)
    logger.info(s"Spawning thread for deep link scraping...")
  }
}

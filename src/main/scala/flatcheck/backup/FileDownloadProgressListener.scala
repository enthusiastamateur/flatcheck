package flatcheck.backup


import com.google.api.client.googleapis.media.MediaHttpDownloader.DownloadState._
import com.google.api.client.googleapis.media.{MediaHttpDownloader, MediaHttpDownloaderProgressListener}
import com.typesafe.scalalogging.LazyLogging
import java.io.IOException
import java.text.NumberFormat

class FileDownloadProgressListener extends MediaHttpDownloaderProgressListener with LazyLogging {
  @throws[IOException]
  override def progressChanged(downloader: MediaHttpDownloader): Unit = {
    downloader.getDownloadState match {
      case NOT_STARTED =>
      case MEDIA_IN_PROGRESS =>
        logger.info("Upload is In Progress: " + NumberFormat.getPercentInstance.format(downloader.getProgress))
      case MEDIA_COMPLETE =>
        logger.info(s"Upload completed")
    }
  }
}

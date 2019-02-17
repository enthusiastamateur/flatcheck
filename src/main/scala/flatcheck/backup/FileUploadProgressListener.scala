package flatcheck.backup

import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.googleapis.media.MediaHttpUploader.UploadState._
import com.typesafe.scalalogging.LazyLogging
import java.io.IOException
import java.text.NumberFormat

class FileUploadProgressListener extends MediaHttpUploaderProgressListener with LazyLogging {
  @throws[IOException]
  override def progressChanged(uploader: MediaHttpUploader): Unit = {
    uploader.getUploadState match {
      case NOT_STARTED =>
      case INITIATION_STARTED =>
        logger.info(s"Upload initiation has started")
      case INITIATION_COMPLETE =>
        logger.info(s"Upload initiation has completed")
      case MEDIA_IN_PROGRESS =>
        logger.info("Upload is In Progress: " + NumberFormat.getPercentInstance.format(uploader.getProgress))
      case MEDIA_COMPLETE =>
        logger.info(s"Upload completed")
    }
  }
}

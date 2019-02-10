package flatcheck.backup

import java.io._
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.services.drive.model.File
import com.google.api.services.drive.{Drive, DriveScopes}
import java.util.Collections
import com.typesafe.scalalogging.LazyLogging
import scala.collection.JavaConverters._


class GDriveBackup(val credentialsFile: String) extends LazyLogging {
  private val APPLICATION_NAME = "flatcheck_cmd"

  /** Directory to store user credentials. */
  private val DATA_STORE_DIR = new java.io.File(System.getProperty("user.home"), ".store/drive_sample")

  private val dataStoreFactory : FileDataStoreFactory = new FileDataStoreFactory(DATA_STORE_DIR)

  /** Global instance of the HTTP transport. */
  private val httpTransport : NetHttpTransport = GoogleNetHttpTransport.newTrustedTransport

  /** Global instance of the JSON factory. */
  private val JSON_FACTORY = JacksonFactory.getDefaultInstance

  /** Global Drive API client. */
  private val drive : Drive = new Drive.Builder(httpTransport, JSON_FACTORY, authorize).setApplicationName(APPLICATION_NAME).build

  /** Authorizes the installed application to access user's protected data. */
  @throws[Exception]
  private def authorize = { // load client secrets
    val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(new FileInputStream(credentialsFile)))
    if (clientSecrets.getDetails.getClientId.startsWith("Enter") || clientSecrets.getDetails.getClientSecret.startsWith("Enter ")) {
      System.out.println("Enter Client ID and Secret from https://code.google.com/apis/console/?api=drive ")
      System.exit(1)
    }
    // set up authorization code flow
    val flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets, Collections.singleton(DriveScopes.DRIVE_FILE)).setDataStoreFactory(dataStoreFactory).build
    // authorize
    new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver).authorize("user")
  }

  // Note: the drive API will only be able to list files created by this app!
  // https://stackoverflow.com/questions/34253889/list-all-files-in-drive
  @throws[IOException]
  private def listFiles() : List[File] = {
    drive.files().list().setPageSize(100)
      .setFields("nextPageToken, files(id, name)")
      .execute().getFiles.asScala.toList
  }

  @throws[IOException]
  private def uploadFile(fileName: String, contentType: String) : Unit = {
    val file = new java.io.File(fileName)
    val fileMetadata = new File()
    fileMetadata.setName(file.getName)
    val mediaContent = new FileContent(contentType, file)
    val request = listFiles().find{ _.getName == fileName}.map{
      existingFile => drive.files.update(existingFile.getId, fileMetadata, mediaContent)
    }.getOrElse{
      logger.info(s"Did not find existing file $fileName in GDrive, creating it...")
      drive.files.create(fileMetadata, mediaContent)
    }
    val uploader = request.getMediaHttpUploader
    uploader.setDirectUploadEnabled(false)
    uploader.setProgressListener(new FileUploadProgressListener())
    request.execute
  }

  @throws[IOException]
  def uploadTextFile(fileName: String) : Unit = {
    uploadFile(fileName, "text/plain")
  }

  @throws[IOException]
  def uploadBinaryFile(fileName: String) : Unit = {
    uploadFile(fileName, "application/octet-stream")
  }

  @throws[IOException]
  def downloadFile(fileName: String): Unit = {
    val outFile = new FileOutputStream(fileName)
    val outStream = new ByteArrayOutputStream()
    val files = listFiles()
    val hit = files.find{ file => file.getName == fileName }
    hit match {
      case Some(file) =>
        logger.info(s"Found file with name $fileName, starting download")
        drive.files.get(file.getId).executeMediaAndDownloadTo(outStream)
        try {
          outStream.writeTo(outFile)
        } finally if (file != null) outFile.close()
      case None =>logger.warn(s"Did not find file with name $fileName, skipping download...")
    }
  }

  @throws[IOException]
  def syncFile(fileName: String, isText: Boolean): Unit = {
    val fileTest = new java.io.File(fileName)
    if (fileTest.exists()) {
      logger.info(s"Running backup of $fileName")
      if (isText) {
        uploadTextFile(fileName)
      } else {
        uploadBinaryFile(fileName)
      }
    } else {
      logger.info(s"Downloading $fileName from GDrive...")
      downloadFile(fileName)
    }
  }
}

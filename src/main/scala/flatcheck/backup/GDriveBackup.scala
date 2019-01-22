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

import com.google.api.client.util.Preconditions
import com.typesafe.scalalogging.LazyLogging


class GDriveBackup(val credentialsFile: String) extends LazyLogging {
  /**
    * Be sure to specify the name of your application. If the application name is {@code null} or
    * blank, the application will log a warning. Suggested format is "MyCompany-ProductName/1.0".
    */
  private val APPLICATION_NAME = ""

  private val UPLOAD_FILE_PATH = "E:\\Dokumentumok\\Scala\\flatcheck\\flatcheck.ini"
  private val DIR_FOR_DOWNLOADS = "E:\\Dokumentumok\\Scala\\"
  private val UPLOAD_FILE = new java.io.File(UPLOAD_FILE_PATH)

  /** Directory to store user credentials. */
  private val DATA_STORE_DIR = new java.io.File(System.getProperty("user.home"), ".store/drive_sample")

  /**
    * Global instance of the {@link DataStoreFactory}. The best practice is to make it a single
    * globally shared instance across your application.
    */
  private var dataStoreFactory : FileDataStoreFactory = _

  /** Global instance of the HTTP transport. */
  private var httpTransport : NetHttpTransport = _

  /** Global instance of the JSON factory. */
  private val JSON_FACTORY = JacksonFactory.getDefaultInstance

  /** Global Drive API client. */
  private var  drive : Drive = _

  /** Authorizes the installed application to access user's protected data. */
  @throws[Exception]
  private def authorize = { // load client secrets
    val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(new FileInputStream(credentialsFile)))
    logger.info(s"The clientsecrets are: $clientSecrets")
    if (clientSecrets.getDetails.getClientId.startsWith("Enter") || clientSecrets.getDetails.getClientSecret.startsWith("Enter ")) {
      System.out.println("Enter Client ID and Secret from https://code.google.com/apis/console/?api=drive " + "into drive-cmdline-sample/src/main/resources/client_secrets.json")
      System.exit(1)
    }
    // set up authorization code flow
    val flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets, Collections.singleton(DriveScopes.DRIVE_FILE)).setDataStoreFactory(dataStoreFactory).build
    // authorize
    new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver).authorize("user")
  }

  def test(): Unit = {
    Preconditions.checkArgument(
      !UPLOAD_FILE_PATH.startsWith("Enter ") && !DIR_FOR_DOWNLOADS.startsWith("Enter "),
      "Please enter the upload file path and download directory in %s", classOf[GDriveBackup])
    try {
      httpTransport = GoogleNetHttpTransport.newTrustedTransport
      dataStoreFactory = new FileDataStoreFactory(DATA_STORE_DIR)
      // authorization
      val credential = authorize
      // set up the global Drive instance
      drive = new Drive.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME).build
      // run commands
      var uploadedFile = uploadFile(false)
      val updatedFile = updateFileWithTestSuffix(uploadedFile.getId)
      downloadFile(false, updatedFile)
      uploadedFile = uploadFile(true)
      downloadFile(true, uploadedFile)
    } catch {
      case e: IOException =>
        System.err.println(e.getMessage)
      case t: Throwable =>
        t.printStackTrace()
    }
  }

  /** Uploads a file using either resumable or direct media upload. */
  @throws[IOException]
  private def uploadFile(useDirectUpload: Boolean) = {
    val fileMetadata = new File()
    fileMetadata.setName(UPLOAD_FILE.getName)
    val mediaContent = new FileContent("image/jpeg", UPLOAD_FILE)
    val insert = drive.files.create(fileMetadata, mediaContent)
    val uploader = insert.getMediaHttpUploader
    uploader.setDirectUploadEnabled(useDirectUpload)
    uploader.setProgressListener(new FileUploadProgressListener())
    insert.execute
  }

  /** Updates the name of the uploaded file to have a "drivetest-" prefix. */
  @throws[IOException]
  private def updateFileWithTestSuffix(id: String) = {
    val fileMetadata = new File()
    fileMetadata.setName("drivetest-" + UPLOAD_FILE.getName)
    val update = drive.files.update(id, fileMetadata)
    update.execute
  }

  /** Downloads a file using either resumable or direct media download. */
  @throws[IOException]
  private def downloadFile(useDirectDownload: Boolean, uploadedFile: File): Unit = { // create parent directory (if necessary)
    val parentDir = new java.io.File(DIR_FOR_DOWNLOADS)
    if (!parentDir.exists && !parentDir.mkdirs) throw new IOException("Unable to create parent directory")
    val file = new FileOutputStream(parentDir + "\\" + uploadedFile.getName)
    val out = new ByteArrayOutputStream()
    drive.files.get(uploadedFile.getId)
      .executeMediaAndDownloadTo(out)
    try {
      out.writeTo(file)
    }
    finally if (file != null) file.close()
  }

}

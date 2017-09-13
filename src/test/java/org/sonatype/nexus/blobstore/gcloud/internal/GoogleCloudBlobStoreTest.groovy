package org.sonatype.nexus.blobstore.gcloud.internal

import java.nio.channels.FileChannel

import org.sonatype.nexus.blobstore.LocationStrategy
import org.sonatype.nexus.blobstore.api.Blob
import org.sonatype.nexus.blobstore.api.BlobId
import org.sonatype.nexus.blobstore.api.BlobStore
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration

import com.google.cloud.storage.Bucket
import com.google.cloud.storage.Storage
import spock.lang.Specification

class GoogleCloudBlobStoreTest
  extends Specification
{

  GoogleCloudStorageFactory storageFactory = Mock()

  LocationStrategy permanentLocationStrategy = Mock()

  LocationStrategy temporaryLocationStrategy =  Mock()

  GoogleCloudBlobStoreMetricsStore metricsStore = Mock()

  Storage storage = Mock()

  Bucket bucket = Mock()

  def blobHeaders = [
      (BlobStore.BLOB_NAME_HEADER): 'test',
      (BlobStore.CREATED_BY_HEADER): 'admin'
  ]
  GoogleCloudBlobStore blobStore = new GoogleCloudBlobStore(
      storageFactory, permanentLocationStrategy, temporaryLocationStrategy, metricsStore)

  def config = new BlobStoreConfiguration()

  static File tempFileBytes
  static File tempFileAttributes

  def setupSpec() {
    tempFileBytes = File.createTempFile('gcloudtest', 'bytes')
    tempFileBytes << 'some blob contents'

    tempFileAttributes = File.createTempFile('gcloudtest', 'properties')
    tempFileAttributes << """\
        |#Thu Jun 01 23:10:55 UTC 2017
        |@BlobStore.created-by=admin
        |size=11
        |@Bucket.repo-name=test
        |creationTime=1496358655289
        |@BlobStore.content-type=text/plain
        |@BlobStore.blob-name=existing
        |sha1=eb4c2a5a1c04ca2d504c5e57e1f88cef08c75707
      """.stripMargin()
  }

  def cleanupSpec() {
    tempFileBytes.delete()
  }

  def setup() {
    permanentLocationStrategy.location(_) >> { args -> args[0].toString() }
    storageFactory.create() >> storage
    config.attributes = [ 'google cloud storage': [bucket: 'mybucket'] ]
  }

  def 'initialize successfully from existing bucket'() {
    given: 'bucket exists'
      storage.get('mybucket') >> bucket

    when: 'init is called'
      blobStore.init(config)

    then: 'no attempt to create'
      0 * storage.create(!null)
  }

  def 'initialize successfully creating bucket'() {
    given: 'bucket does not exist'
      storage.get('mybucket') >> null

    when: 'init is called'
      blobStore.init(config)

    then: 'no attempt to create'
      1 * storage.create(!null)
  }

  def 'store a blob successfully'() {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()

    when: 'call create'
      Blob blob = blobStore.create(new ByteArrayInputStream('hello world'.bytes), blobHeaders)

    then: 'blob stored'
      blob != null
  }

  def 'read blob inputstream'() {
    given: 'blobstore setup'
      storage.get('mybucket') >> bucket
      blobStore.init(config)
      blobStore.doStart()
      bucket.get('content/existing.properties') >> mockGoogleObject(tempFileAttributes)
      bucket.get('content/existing.bytes') >> mockGoogleObject(tempFileBytes)

    when: 'call create'
      Blob blob = blobStore.get(new BlobId('existing'))

    then: 'blob contains expected content'
      blob.getInputStream().text == 'some blob contents'
  }

  private mockGoogleObject(File file) {
    com.google.cloud.storage.Blob blob = Mock()
    blob.reader() >> new DelegatingReadChannel(FileChannel.open(file.toPath()))
    blob
  }
}

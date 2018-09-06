package org.sonatype.nexus.blobstore.gcloud.internal.attributes

import java.nio.file.FileSystems

import org.sonatype.nexus.blobstore.api.BlobAttributes
import org.sonatype.nexus.blobstore.api.BlobId
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration
import org.sonatype.nexus.blobstore.file.FileBlobAttributes
import org.sonatype.nexus.blobstore.gcloud.internal.GoogleCloudDatastoreFactory

import com.google.cloud.datastore.Datastore
import com.google.cloud.datastore.Entity
import com.google.cloud.datastore.Query
import com.google.cloud.datastore.QueryResults
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

class DatastoreBlobAttributesDaoIT
  extends Specification
{
  static final Logger log = LoggerFactory.getLogger(DatastoreBlobAttributesDaoIT.class)

  static final String testUniqueID = UUID.randomUUID().toString()

  static final BlobStoreConfiguration config = new BlobStoreConfiguration()

  static File tempFileAttributes

  DatastoreBlobAttributesDao attributesDao

  def setupSpec() {
    config.attributes = [
        'google cloud storage': [
            credential_file: this.getClass().getResource('/gce-credentials.json').getFile()
        ]
    ]
    config.name = testUniqueID

    tempFileAttributes = File.createTempFile('gcloudtest', 'properties')
    tempFileAttributes << """\
      |@BlobStore.created-by=admin
      |size=32
      |@Bucket.repo-name=maven-snapshots
      |creationTime=1535056281314
      |@BlobStore.created-by-ip=127.0.0.1
      |@BlobStore.content-type=text/plain
      |@BlobStore.blob-name=local/sonatype/group1/test-project/0.10.0-SNAPSHOT/test-project-0.10.0-20180823.203101-1.pom.md5
      |sha1=4f0fc3951c87bae5a38f53c4f872ad0fb9faef54
    """.stripMargin()

    log.info("attributes for test will be stored with key kind suffix {}", testUniqueID)
  }
  def setup() {
    Datastore datastore = new GoogleCloudDatastoreFactory().create(config)

    attributesDao = new DatastoreBlobAttributesDao(datastore, config)
  }

  def cleanupSpec() {
    Datastore datastore = new GoogleCloudDatastoreFactory().create(config)
    DatastoreBlobAttributesDao dao = new DatastoreBlobAttributesDao(datastore, config)
    Query<Entity> query = Query.newEntityQueryBuilder()
        .setKind(dao.attributesKeyKind)
        .build()
    QueryResults<Entity> results = datastore.run(query)
    log.info("deleting attributes created under key kind {}", dao.attributesKeyKind)
    while (results.hasNext()) {
      datastore.delete(results.next().getKey())
    }
    log.info('cleanupSpec complete')
  }

  def 'storeAttributes successfully stores properties'() {
    given:
      BlobId id = new BlobId('testing')
      BlobAttributes blobAttributes = new FileBlobAttributes(FileSystems.getDefault().getPath(tempFileAttributes.getPath()))
      assert blobAttributes.load()
      assert blobAttributes.metrics != null

    when:
      BlobAttributes stored = attributesDao.storeAttributes(id, blobAttributes)

    then:
      stored != null
      BlobAttributes readback = attributesDao.getAttributes(id)
      readback != null
      stored.headers == readback.headers
      stored.metrics.contentSize == readback.metrics.contentSize
      stored.metrics.creationTime == readback.metrics.creationTime
      stored.metrics.sha1Hash == readback.metrics.sha1Hash
  }

  def 'markDeleted successfully sets deleted'() {
    given:
      BlobId id = new BlobId('testing2')
      BlobAttributes blobAttributes = new FileBlobAttributes(FileSystems.getDefault().getPath(tempFileAttributes.getPath()))
      assert blobAttributes.load()
      assert blobAttributes.metrics != null
      assert !blobAttributes.deleted
      assert attributesDao.storeAttributes(id, blobAttributes) != null

    when:
      attributesDao.markDeleted(id, 'testreason')

    then:
      BlobAttributes readback = attributesDao.getAttributes(id)
      readback != null
      readback.deleted
      readback.deletedReason =='testreason'
  }
}
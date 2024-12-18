package cbl.js.kotiln

import cbl.js.kotlin.CollectionDocumentResult
import com.couchbase.lite.ConcurrencyControl
import com.couchbase.lite.Document
import com.couchbase.lite.Index
import com.couchbase.lite.Blob
import com.couchbase.lite.MutableDocument
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CollectionManager {

    @Throws(Exception::class)
    fun createIndex(indexName: String,
                    index: Index,
                    collectionName: String,
                    scopeName: String,
                    databaseName: String) {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.createIndex(indexName, index)
    }

    @Throws(Exception::class)
    fun deleteDocument(documentId: String,
                       collectionName: String,
                       scopeName: String,
                       databaseName: String): Boolean {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.let { collection ->
            val doc = collection.getDocument(documentId)
            doc?.let { document ->
                collection.delete(document)
                return true
            }
            throw Exception("Error: Document not found")
        }
        throw Exception("Error: Collection not found")
    }

    @Throws(Exception::class)
    fun deleteDocument(documentId: String,
                       collectionName: String,
                       scopeName: String,
                       databaseName: String,
                       concurrencyControl: ConcurrencyControl): Boolean {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.let { collection ->
            val doc = collection.getDocument(documentId)
            doc?.let { document ->
                val result = collection.delete(document, concurrencyControl)
                return result
            }
            throw Exception("Error: Document not found")
        }
        throw Exception("Error: Collection not found")
    }

    @Throws(Exception::class)
    fun deleteIndex(indexName: String,
                    collectionName: String,
                    scopeName: String,
                    databaseName: String) {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.deleteIndex(indexName)
    }

    @Throws(Exception::class)
    fun documentsCount(
        collectionName: String,
        scopeName: String,
        databaseName: String
    ): Int {
        var count = 0
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.let { collection ->
            count = collection.count.toInt()
        }
        return count
    }

    @Throws(Exception::class)
    fun getBlobContent(key: String,
                       documentId: String,
                       collectionName: String,
                       scopeName: String,
                       databaseName: String): ByteArray? {

        val doc = this.getDocument(documentId, collectionName, scopeName, databaseName)
        doc?.let { document ->
            val blob = document.getBlob(key)
            blob?.let { b ->
                return b.content
            }
        }
        return null
    }


    /**
     * Converts a JSON string representation of blobs into a map of Blob objects.
     *
     * @param value The JSON string containing blob data. The string should be in the format:
     *              {
     *                  "blobKey1": {
     *                      "data": {
     *                          "contentType": "mime/type",
     *                          "data": [byte1, byte2, ...]
     *                      }
     *                  },
     *                  "blobKey2": {
     *                      "data": {
     *                          "contentType": "mime/type",
     *                          "data": [byte1, byte2, ...]
     *                      }
     *                  }
     *              }
     *              If the string is empty or "[]", an empty map is returned.
     * @return A map where the keys are the blob identifiers and the values are Blob objects.
     * @throws Exception If the JSON string is malformed or if required fields are missing.
     */
    @Throws(Exception::class)
    fun getBlobsFromString(
        value: String):Map<String, Blob>
    {
        val items = mutableMapOf<String, Blob>()
        if (value.isEmpty() || value == "[]") {
            return items
        }
        val jsonObject = JSONObject(value)
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val jsonValue = jsonObject[key]
            if (jsonValue is JSONObject) {
                val blobData = jsonValue.getJSONObject("data")
                val contentType = blobData.getString("contentType")
                val byteData = blobData.getJSONArray("data")
                val data = ByteArray(byteData.length())
                for (i in 0 until byteData.length()) {
                    data[i] = (byteData[i] as Int).toByte()
                }
                items[key] = Blob(contentType, data)
            }
        }
        return items
    }

    @Throws(Exception::class)
    private fun getCollection(
        collectionName: String,
        scopeName: String,
        databaseName: String
    ): CBLCollection? {
        return DatabaseManager.getDatabase(databaseName)?.getCollection(collectionName, scopeName)
    }

    @Throws(Exception::class)
    fun getDocument(documentId: String,
                    collectionName: String,
                    scopeName: String,
                    databaseName: String): Document? {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.let { collection ->
            val doc = collection.getDocument(documentId)
            return doc
        }
        return null
    }

    @Throws(Exception::class)
    fun getDocumentExpiration(documentId: String,
                              collectionName: String,
                              scopeName: String,
                              databaseName: String): Date? {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        return col?.getDocumentExpiration(documentId)
    }

    @Throws(Exception::class)
    fun getIndexes(collectionName: String,
                   scopeName: String,
                   databaseName: String): Set<String>? {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        return col?.getIndexes()
    }

    @Throws(Exception::class)
    fun purgeDocument(documentId: String,
                      collectionName: String,
                      scopeName: String,
                      databaseName: String) {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        col?.purge(documentId)
    }

    @Throws(Exception::class)
    fun saveDocument(
        documentId: String,
        document: String,
        blobs: String,
        concurrencyControl: ConcurrencyControl?,
        collectionName: String,
        scopeName: String,
        databaseName: String
    ) : CollectionDocumentResult {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        val blobsMap = this.getBlobsFromString(blobs)
        col?.let { collection ->
            val mutableDocument =  if (documentId.isEmpty()) {
                MutableDocument(document)
            } else {
                MutableDocument(documentId, document)
            }
            if (blobs.isNotEmpty()){
                for ((key, value) in blobsMap) {
                    mutableDocument.setBlob(key, value)
                }
            }
            concurrencyControl?.let {
                val result = collection.save(mutableDocument, it)
                if (result) {
                    return CollectionDocumentResult(
                        mutableDocument.id,
                        mutableDocument.revisionID,
                        mutableDocument.sequence,
                        true
                    )
                } else {
                    return CollectionDocumentResult(
                        mutableDocument.id,
                        mutableDocument.revisionID,
                        mutableDocument.sequence,
                        false
                    )
                }
            }
            collection.save(mutableDocument)
            return CollectionDocumentResult(
                mutableDocument.id,
                mutableDocument.revisionID,
                mutableDocument.sequence,
                null
            )
        }
        throw Error("Error: Document not saved")
    }

    @Throws(Exception::class)
    fun setDocumentExpiration(documentId: String,
                              expiration: String,
                              collectionName: String,
                              scopeName: String,
                              databaseName: String) {
        val col = this.getCollection(collectionName, scopeName, databaseName)
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        val expirationDate = format.parse(expiration)
        col?.setDocumentExpiration(documentId, expirationDate)
    }
}

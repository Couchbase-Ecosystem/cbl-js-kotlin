package com.cblreactnative

import android.util.Log
import com.couchbase.lite.Database
import com.couchbase.lite.DatabaseConfiguration
import com.couchbase.lite.ReplicatorConfiguration

object CouchbaseReflectionHelper {
    
    fun setEncryptionKey(config: DatabaseConfiguration, encryptionKey: String) {
        try { 
            val encryptionKeyClass = Class.forName("com.couchbase.lite.EncryptionKey")
            val encryptionKeyInstance = encryptionKeyClass.getConstructor(String::class.java).newInstance(encryptionKey)
            val setMethod = config.javaClass.getMethod("setEncryptionKey", encryptionKeyClass)
            setMethod.invoke(config, encryptionKeyInstance)
        } catch (e: Exception) {
            Log.w("CouchbaseLite", "Failed to set encryption key: ${e.message}")
        }
    }
    
    fun changeEncryptionKey(database: Database, encryptionKey: String?) {
        try { 
            val encryptionKeyClass = Class.forName("com.couchbase.lite.EncryptionKey")
            val encryptionKeyInstance = if (encryptionKey != null) {
                encryptionKeyClass.getConstructor(String::class.java).newInstance(encryptionKey)
            } else null
            
            val changeMethod = database.javaClass.getMethod("changeEncryptionKey", encryptionKeyClass)
            changeMethod.invoke(database, encryptionKeyInstance)
        } catch (e: Exception) {
            Log.w("CouchbaseLite", "Failed to change encryption key: ${e.message}")
        }
    }
    
    fun setAcceptOnlySelfSignedServerCertificate(config: ReplicatorConfiguration, value: Boolean) {
        try { 
            val method = config.javaClass.getMethod("setAcceptOnlySelfSignedServerCertificate", Boolean::class.javaPrimitiveType)
            method.invoke(config, value)
        } catch (e: Exception) {
            Log.w("CouchbaseLite", "Failed to set certificate option: ${e.message}")
        }
    }
    
    fun isAcceptOnlySelfSignedServerCertificate(config: ReplicatorConfiguration): Boolean {
        return try { 
            val method = config.javaClass.getMethod("isAcceptOnlySelfSignedServerCertificate")
            method.invoke(config) as Boolean
        } catch (e: Exception) {
            Log.w("CouchbaseLite", "Failed to get certificate option: ${e.message}")
            false 
        }
    }
}
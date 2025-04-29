package cbl.js.kotiln

import android.util.Log
import com.couchbase.lite.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableArray
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import com.couchbase.lite.Collection as CBLCollection

object ReplicatorHelper {
    private const val TAG = "ReplicatorHelper"

    /**
     * Creates a Replicator configuration from a ReadableMap
     */
    @Throws(Exception::class)
    fun replicatorConfigFromJson(config: ReadableMap): ReplicatorConfiguration {
        try {
            // Parse endpoint from URL
            val targetConfig = config.getMap("target")
                ?: throw Exception("Target configuration is required")
            val urlString = targetConfig.getString("url")
                ?: throw Exception("Target URL is required")
            
            // Get replicator type
            val replicatorTypeStr = config.getString("replicatorType") ?: "PUSH_AND_PULL"
            val replicatorType = getReplicatorTypeFromString(replicatorTypeStr)
            
            val endpoint = URLEndpoint(URI(urlString))
            
            // Create basic configuration with essential properties
            val replicatorConfig = ReplicatorConfiguration(endpoint)
            replicatorConfig.type = replicatorType
            replicatorConfig.isContinuous = config.getBoolean("continuous")
            
            // Set other properties
            try {
                replicatorConfig.isAcceptOnlySelfSignedServerCertificate = config.getBoolean("acceptSelfSignedCerts")
            } catch (e: Exception) {
                Log.d(TAG, "Could not set acceptSelfSignedCerts: ${e.message}")
            }
            
            // Set numeric properties
            try {
                replicatorConfig.heartbeat = config.getDouble("heartbeat").toInt()
                replicatorConfig.maxAttempts = config.getInt("maxAttempts")
                replicatorConfig.maxAttemptWaitTime = config.getDouble("maxAttemptWaitTime").toInt()
            } catch (e: Exception) {
                Log.d(TAG, "Could not set numeric properties: ${e.message}")
            }
            
            // Set headers if present
            if (config.hasKey("headers") && config.getType("headers") == ReadableType.Map) {
                val headers = config.getMap("headers")
                val headerMap = HashMap<String, String>()
                headers?.entryIterator?.forEach { entry ->
                    if (entry.value is String) {
                        headerMap[entry.key] = entry.value as String
                    }
                }
                if (headerMap.isNotEmpty()) {
                    replicatorConfig.headers = headerMap
                }
            }
            
            // Set authenticator if present
            if (config.hasKey("authenticator") && config.getType("authenticator") == ReadableType.Map) {
                val authConfig = config.getMap("authenticator")
                if (authConfig != null) {
                    val authenticator = createAuthenticator(authConfig)
                    if (authenticator != null) {
                        replicatorConfig.authenticator = authenticator
                    }
                }
            }
            
            // Process collections configuration
            if (config.hasKey("collectionConfig") && config.getType("collectionConfig") == ReadableType.String) {
                val collectionConfigStr = config.getString("collectionConfig")
                if (!collectionConfigStr.isNullOrEmpty()) {
                    processCollectionConfig(collectionConfigStr, replicatorConfig)
                }
            }
            
            return replicatorConfig
        } catch (e: Exception) {
            Log.e(TAG, "Error creating replicator config: ${e.message}")
            throw e
        }
    }
    
    /**
     * Process collection configuration and add to replicator config
     */
    @Throws(Exception::class)
    private fun processCollectionConfig(configJson: String, replicatorConfig: ReplicatorConfiguration) {
        try {
            val collectionConfigArray = JSONArray(configJson)
            
            for (i in 0 until collectionConfigArray.length()) {
                val collectionConfigItem = collectionConfigArray.getJSONObject(i)
                
                // Process collections
                val collections = ArrayList<CBLCollection>()
                val collectionsArray = collectionConfigItem.getJSONArray("collections")
                
                if (collectionsArray.length() == 0) {
                    throw Exception("No collections found in configuration")
                }
                
                for (j in 0 until collectionsArray.length()) {
                    val collectionWrapper = collectionsArray.getJSONObject(j)
                    val collectionData = collectionWrapper.getJSONObject("collection")
                    
                    val dbName = collectionData.getString("databaseName")
                    val scopeName = collectionData.getString("scopeName")
                    val collectionName = collectionData.getString("name")
                    
                    val collection = DatabaseManager.getCollection(collectionName, scopeName, dbName)
                        ?: throw Exception("Collection not found: $scopeName.$collectionName in database $dbName")
                    
                    collections.add(collection)
                }
                
                // Process config (channels & documentIds)
                val collectionConfig = CollectionConfiguration()
                val configData = collectionConfigItem.optJSONObject("config")
                
                if (configData != null) {
                    // Process channels
                    if (configData.has("channels")) {
                        val channelsArray = configData.getJSONArray("channels")
                        val channels = ArrayList<String>()
                        for (j in 0 until channelsArray.length()) {
                            channels.add(channelsArray.getString(j))
                        }
                        if (channels.isNotEmpty()) {
                            collectionConfig.channels = channels
                        }
                    }
                    
                    // Process documentIds
                    if (configData.has("documentIds")) {
                        val docIdsArray = configData.getJSONArray("documentIds")
                        val documentIds = ArrayList<String>()
                        for (j in 0 until docIdsArray.length()) {
                            documentIds.add(docIdsArray.getString(j))
                        }
                        if (documentIds.isNotEmpty()) {
                            collectionConfig.documentIDs = documentIds
                        }
                    }
                }
                
                // Add collections to replicator config
                replicatorConfig.addCollections(collections, collectionConfig)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error processing collection config: ${e.message}")
            throw Exception("Invalid collection configuration format: ${e.message}")
        }
    }
    
    /**
     * Get ReplicatorType from string representation
     */
    private fun getReplicatorTypeFromString(typeStr: String): ReplicatorType {
        return when (typeStr) {
            "PUSH" -> ReplicatorType.PUSH
            "PULL" -> ReplicatorType.PULL
            else -> ReplicatorType.PUSH_AND_PULL
        }
    }
    
    /**
     * Creates an authenticator from configuration
     */
    private fun createAuthenticator(authConfig: ReadableMap): Authenticator? {
        val type = authConfig.getString("type") ?: return null
        val data = authConfig.getMap("data") ?: return null
        
        return when (type) {
            "basic" -> {
                val username = data.getString("username") ?: return null
                val password = data.getString("password") ?: return null
                BasicAuthenticator(username, password.toCharArray())
            }
            "session" -> {
                val sessionId = data.getString("sessionID") ?: return null
                val cookieName = data.getString("cookieName") ?: return null
                SessionAuthenticator(sessionId, cookieName)
            }
            else -> null
        }
    }
    
    /**
     * Generates a map representation of replicator status
     */
    fun generateReplicatorStatusMap(status: ReplicatorStatus): WritableMap {
        val resultMap = Arguments.createMap()
        
        // Process activity level
        resultMap.putInt("activityLevel", status.activityLevel.ordinal)
        
        // Process progress
        val progressMap = Arguments.createMap()
        progressMap.putInt("completed", status.progress.completed.toInt())
        progressMap.putInt("total", status.progress.total.toInt())
        resultMap.putMap("progress", progressMap)
        
        // Process error if present
        if (status.error != null) {
            val errorMap = Arguments.createMap()
            errorMap.putString("message", status.error?.message)
            resultMap.putMap("error", errorMap)
        }
        
        return resultMap
    }
    
    /**
     * Generates a map representation of document replication data
     */
    fun generateDocumentReplicationMap(documents: List<ReplicatedDocument>, isPush: Boolean): WritableMap {
        val resultMap = Arguments.createMap()
        val docsArray = Arguments.createArray()
        
        // Add isPush flag
        resultMap.putBoolean("isPush", isPush)
        
        // Process each document
        for (document in documents) {
            val docMap = Arguments.createMap()
            
            docMap.putString("id", document.id)
            docMap.putString("scopeName", document.scope)
            docMap.putString("collectionName", document.collection)
            
            // Process flags
            val flagsArray = Arguments.createArray()
            if (document.flags.contains(DocumentFlag.DELETED)) {
                flagsArray.pushString("DELETED")
            }
            if (document.flags.contains(DocumentFlag.ACCESS_REMOVED)) {
                flagsArray.pushString("ACCESS_REMOVED")
            }
            docMap.putArray("flags", flagsArray)
            
            // Process error if present
            if (document.error != null) {
                val errorMap = Arguments.createMap()
                errorMap.putString("message", document.error?.message)
                docMap.putMap("error", errorMap)
            }
            
            docsArray.pushMap(docMap)
        }
        
        resultMap.putArray("documents", docsArray)
        return resultMap
    }
}
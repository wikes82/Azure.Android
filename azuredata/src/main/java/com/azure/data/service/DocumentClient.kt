package com.azure.data.service

import com.azure.core.http.HttpHeader
import com.azure.core.http.HttpMediaType
import com.azure.core.http.HttpMethod
import com.azure.core.http.HttpStatusCode
import com.azure.core.log.d
import com.azure.core.log.e
import com.azure.core.network.NetworkConnectivity
import com.azure.core.network.NetworkConnectivityManager
import com.azure.core.util.ContextProvider
import com.azure.core.util.DateUtil
import com.azure.data.constants.HttpHeaderValue
import com.azure.data.constants.MSHttpHeader
import com.azure.data.model.*
import com.azure.data.model.indexing.IndexingPolicy
import com.azure.data.util.*
import com.azure.data.util.json.ResourceListJsonDeserializer
import com.azure.data.util.json.gson
import com.google.gson.reflect.TypeToken
import getDefaultHeaders
import okhttp3.*
import java.io.IOException
import java.lang.reflect.Type
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

class DocumentClient private constructor() {

    private var host: String? = null

    private var permissionProvider: PermissionProvider? = null

    private var resourceTokenProvider: ResourceTokenProvider? = null

    private var isOffline = false

    var connectivityManager: NetworkConnectivityManager? = null
        set(value) {
            if (isConfigured && value != null) {
                value.registerListener(networkConnectivityChanged)
                value.startListening()
            }
        }

    val configuredWithMasterKey: Boolean
        get() = resourceTokenProvider != null

    fun configure(accountName: String, masterKey: String, permissionMode: PermissionMode) {

        resourceTokenProvider = ResourceTokenProvider(masterKey, permissionMode)

        commonConfigure("$accountName.documents.azure.com")
    }

    fun configure(accountUrl: URL, masterKey: String, permissionMode: PermissionMode) {

        resourceTokenProvider = ResourceTokenProvider(masterKey, permissionMode)

        commonConfigure(accountUrl.host)
    }

    fun configure(accountUrl: HttpUrl, masterKey: String, permissionMode: PermissionMode) {

        resourceTokenProvider = ResourceTokenProvider(masterKey, permissionMode)

        commonConfigure(accountUrl.host())
    }

    fun configure(accountName: String, permissionProvider: PermissionProvider) {

        this.permissionProvider = permissionProvider

        commonConfigure("$accountName.documents.azure.com")
    }

    fun configure(accountUrl: URL, permissionProvider: PermissionProvider) {

        this.permissionProvider = permissionProvider

        commonConfigure(accountUrl.host)
    }

    fun configure(accountUrl: HttpUrl, permissionProvider: PermissionProvider) {

        this.permissionProvider = permissionProvider

        commonConfigure(accountUrl.host())
    }

    val isConfigured: Boolean
        get() = !host.isNullOrEmpty() && (resourceTokenProvider != null || permissionProvider != null)

    // base headers... grab these once and then re-serve
    private val headers: Headers by lazy {
        ContextProvider.appContext.getDefaultHeaders()
    }

    private fun commonConfigure(host: String) {

        if (host.isEmpty()) {
            throw Exception("Host is invalid")
        }

        this.host = host

        ResourceOracle.init(ContextProvider.appContext, host)
        PermissionCache.init(host)

        connectivityManager = NetworkConnectivity.manager

    }

    fun reset () {

        host = null
        permissionProvider = null
        resourceTokenProvider = null
    }

    //region Network Connectivity

    private val networkConnectivityChanged: (Boolean) -> Unit = { isConnected ->
        d { "Network Status Changed: ${if (isConnected) "Connected" else "Not Connected"}" }
        this.isOffline = !isConnected

        if (isConnected) {
            ResourceWriteOperationQueue.shared.sync()
        }
    }

    //endregion

    //region Database

    // create
    fun createDatabase(databaseId: String, callback: (Response<Database>) -> Unit)
            = create(Database(databaseId), ResourceLocation.Database(), callback = callback)

    // list
    fun getDatabases(maxPerPage: Int? = null, callback: (ListResponse<Database>) -> Unit) {

        return resources(ResourceLocation.Database(), callback, maxPerPage = maxPerPage, resourceClass = Database::class.java)
    }

    // get
    fun getDatabase(databaseId: String, callback: (Response<Database>) -> Unit) {

        return resource(ResourceLocation.Database(databaseId), callback, resourceClass = Database::class.java)
    }

    // delete
    fun deleteDatabase(databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Database(databaseId), callback)
    }

    //endregion

    //region Collections

    // create
    fun createCollection(collectionId: String, databaseId: String, callback: (Response<DocumentCollection>) -> Unit) {

        return create(DocumentCollection(collectionId), ResourceLocation.Collection(databaseId), callback = callback)
    }

    // list
    fun getCollectionsIn(databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<DocumentCollection>) -> Unit) {

        return resources(ResourceLocation.Collection(databaseId), callback, maxPerPage = maxPerPage, resourceClass = DocumentCollection::class.java)
    }

    // get
    fun getCollection(collectionId: String, databaseId: String, callback: (Response<DocumentCollection>) -> Unit) {

        return resource(ResourceLocation.Collection(databaseId, collectionId), callback, resourceClass = DocumentCollection::class.java)
    }

    // delete
    fun deleteCollection(collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Collection(databaseId, collectionId), callback)
    }

    // replace
    fun replaceCollection(collectionId: String, databaseId: String, indexingPolicy: IndexingPolicy, callback: (Response<DocumentCollection>) -> Unit) {

        return replace(DocumentCollection(collectionId, indexingPolicy), ResourceLocation.Collection(databaseId, collectionId), callback = callback)
    }

    //endregion

    //region Documents

    // create
    fun <T : Document> createDocument(document: T, collectionId: String, databaseId: String, callback: (Response<T>) -> Unit) {

        return create(document, ResourceLocation.Document(databaseId, collectionId), callback = callback)
    }

    // create
    fun <T : Document> createDocument (document: T, collection: DocumentCollection, callback: (Response<T>) -> Unit) {

        return create(document, ResourceLocation.Child(ResourceType.Document, collection), callback = callback)
    }

    // createOrReplace
    fun <T : Document> createOrReplaceDocument(document: T, collectionId: String, databaseId: String, callback: (Response<T>) -> Unit) {

        return create(document, ResourceLocation.Document(databaseId, collectionId), additionalHeaders = Headers.of(mapOf(Pair(MSHttpHeader.MSDocumentDBIsUpsert.value, "true"))), callback = callback)
    }

    // createOrReplace
    fun <T : Document> createOrReplaceDocument (document: T, collection: DocumentCollection, callback: (Response<T>) -> Unit) {

        return create(document, ResourceLocation.Child(ResourceType.Document, collection), additionalHeaders = Headers.of(mapOf(Pair(MSHttpHeader.MSDocumentDBIsUpsert.value, "true"))), callback = callback)
    }

    // list
    fun <T : Document> getDocumentsAs(collectionId: String, databaseId: String, documentClass: Class<T>, maxPerPage: Int? = null, callback: (ListResponse<T>) -> Unit) {

        return resources(ResourceLocation.Document(databaseId, collectionId), callback, documentClass, maxPerPage)
    }

    // list
    fun <T : Document> getDocumentsAs(collection: DocumentCollection, documentClass: Class<T>, maxPerPage: Int? = null, callback: (ListResponse<T>) -> Unit) {

        return resources(ResourceLocation.Child(ResourceType.Document, collection), callback, documentClass, maxPerPage)
    }

    // get
    fun <T : Document> getDocument(documentId: String, collectionId: String, databaseId: String, documentClass: Class<T>, callback: (Response<T>) -> Unit) {

        return resource(ResourceLocation.Document(databaseId, collectionId, documentId), callback, documentClass)
    }

    // get
    fun <T : Document> getDocument(documentId: String, collection: DocumentCollection, documentClass: Class<T>, callback: (Response<T>) -> Unit) {

        return resource(ResourceLocation.Child(ResourceType.Document, collection, documentId), callback, documentClass)
    }

    // delete
    fun deleteDocument(documentId: String, collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Document(databaseId, collectionId, documentId), callback)
    }

    // delete
    fun deleteDocument(documentId: String, collection: DocumentCollection, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Child(ResourceType.Document, collection, documentId), callback)
    }

    // replace
    fun <T : Document> replaceDocument(document: T, collectionId: String, databaseId: String, callback: (Response<T>) -> Unit) {

        return replace(document, ResourceLocation.Document(databaseId, collectionId, document.id), callback = callback)
    }

    // replace
    fun <T : Document> replaceDocument(document: T, collection: DocumentCollection, callback: (Response<T>) -> Unit) {

        return replace(document, ResourceLocation.Child(ResourceType.Document, collection, document.id), callback = callback)
    }

    // query
    fun <T: Document> queryDocuments (collectionId: String, databaseId: String, query: Query, documentClass: Class<T>, maxPerPage: Int? = null, callback: (ListResponse<T>) -> Unit) {

        return query(query, ResourceLocation.Document(databaseId, collectionId), maxPerPage, callback, documentClass)
    }

    // query
    fun <T: Document> queryDocuments (collection: DocumentCollection, query: Query, documentClass: Class<T>, maxPerPage: Int? = null, callback: (ListResponse<T>) -> Unit) {

        return query(query, ResourceLocation.Child(ResourceType.Document, collection), maxPerPage, callback, documentClass)
    }

    //endregion

    //region Attachments

    // create
    fun createAttachment(attachmentId: String, contentType: String, mediaUrl: HttpUrl, documentId: String, collectionId: String, databaseId: String, callback: (Response<Attachment>) -> Unit) {

        return create(Attachment(attachmentId, contentType, mediaUrl.toString()), ResourceLocation.Attachment(databaseId, collectionId, documentId), callback = callback)
    }

    // create
    fun createAttachment(attachmentId: String, contentType: String, media: ByteArray, documentId: String, collectionId: String, databaseId: String, callback: (Response<Attachment>) -> Unit) {

        val headers = Headers.Builder()
                .add(HttpHeader.ContentType.value, contentType)
                .add(HttpHeader.Slug.value, attachmentId)
                .build()

        return createOrReplace(media, ResourceLocation.Attachment(databaseId, collectionId, documentId), additionalHeaders = headers, callback = callback)
    }

    // create
    fun createAttachment(attachmentId: String, contentType: String, mediaUrl: HttpUrl, document: Document, callback: (Response<Attachment>) -> Unit) {

        return create(Attachment(attachmentId, contentType, mediaUrl.toString()), ResourceLocation.Child(ResourceType.Attachment, document), callback = callback)
    }

    // create
    fun createAttachment(attachmentId: String, contentType: String, media: ByteArray, document: Document, callback: (Response<Attachment>) -> Unit) {

        val headers = Headers.Builder()
                .add(HttpHeader.ContentType.value, contentType)
                .add(HttpHeader.Slug.value, attachmentId)
                .build()

        return createOrReplace(media, ResourceLocation.Child(ResourceType.Attachment, document), additionalHeaders = headers, callback = callback)
    }

    // list
    fun getAttachments(documentId: String, collectionId: String, databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<Attachment>) -> Unit) {

        return resources(ResourceLocation.Attachment(databaseId, collectionId, documentId), callback, maxPerPage = maxPerPage, resourceClass = Attachment::class.java)
    }

    // list
    fun getAttachments(document: Document, maxPerPage: Int? = null, callback: (ListResponse<Attachment>) -> Unit) {

        return resources(ResourceLocation.Child(ResourceType.Attachment, document), callback, maxPerPage = maxPerPage, resourceClass = Attachment::class.java)
    }

    // delete
    fun deleteAttachment(attachmentId: String, documentId: String, collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Attachment(databaseId, collectionId, documentId, attachmentId), callback)
    }

    // delete
    fun deleteAttachment(attachmentId: String, document: Document, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Child(ResourceType.Attachment, document, attachmentId), callback)
    }

    // replace
    fun replaceAttachment(attachmentId: String, contentType: String, mediaUrl: HttpUrl, documentId: String, collectionId: String, databaseId: String, callback: (Response<Attachment>) -> Unit) {

        return replace(Attachment(attachmentId, contentType, mediaUrl.toString()), ResourceLocation.Attachment(databaseId, collectionId, documentId, attachmentId), callback = callback)
    }

    // replace
    fun replaceAttachment(attachmentId: String, contentType: String, media: ByteArray, documentId: String, collectionId: String, databaseId: String, callback: (Response<Attachment>) -> Unit) {

        val headers = Headers.Builder()
                .add(HttpHeader.ContentType.value, contentType)
                .add(HttpHeader.Slug.value, attachmentId)
                .build()

        return createOrReplace(media, ResourceLocation.Attachment(databaseId, collectionId, documentId, attachmentId), replacing = true, additionalHeaders = headers, callback = callback)
    }

    // replace
    fun replaceAttachment(attachmentId: String, contentType: String, mediaUrl: HttpUrl, document: Document, callback: (Response<Attachment>) -> Unit) {

        return replace(Attachment(attachmentId, contentType, mediaUrl.toString()), ResourceLocation.Child(ResourceType.Attachment, document, attachmentId), callback = callback)
    }

    // replace
    fun replaceAttachment(attachmentId: String, contentType: String, media: ByteArray, document: Document, callback: (Response<Attachment>) -> Unit) {

        val headers = Headers.Builder()
                .add(HttpHeader.ContentType.value, contentType)
                .add(HttpHeader.Slug.value, attachmentId)
                .build()

        return createOrReplace(media, ResourceLocation.Child(ResourceType.Attachment, document, attachmentId), replacing = true, additionalHeaders = headers, callback = callback)
    }

    //endregion

    //region Stored Procedures

    // create
    fun createStoredProcedure(storedProcedureId: String, procedure: String, collectionId: String, databaseId: String, callback: (Response<StoredProcedure>) -> Unit) {

        return create(StoredProcedure(storedProcedureId, procedure), ResourceLocation.StoredProcedure(databaseId, collectionId), callback = callback)
    }

    // create
    fun createStoredProcedure(storedProcedureId: String, procedure: String, collection: DocumentCollection, callback: (Response<StoredProcedure>) -> Unit) {

        return create(StoredProcedure(storedProcedureId, procedure), ResourceLocation.Child(ResourceType.StoredProcedure, collection), callback = callback)
    }

    // list
    fun getStoredProcedures(collectionId: String, databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<StoredProcedure>) -> Unit) {

        return resources(ResourceLocation.StoredProcedure(databaseId, collectionId), callback, maxPerPage = maxPerPage, resourceClass = StoredProcedure::class.java)
    }

    // list
    fun getStoredProcedures(collection: DocumentCollection, maxPerPage: Int? = null, callback: (ListResponse<StoredProcedure>) -> Unit) {

        return resources(ResourceLocation.Child(ResourceType.StoredProcedure, collection), callback, maxPerPage = maxPerPage, resourceClass = StoredProcedure::class.java)
    }

    // delete
    fun deleteStoredProcedure(storedProcedureId: String, collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.StoredProcedure(databaseId, collectionId, storedProcedureId), callback)
    }

    // delete
    fun deleteStoredProcedure(storedProcedureId: String, collection: DocumentCollection, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Child(ResourceType.StoredProcedure, collection, storedProcedureId), callback)
    }

    // replace
    fun replaceStoredProcedure(storedProcedureId: String, procedure: String, collectionId: String, databaseId: String, callback: (Response<StoredProcedure>) -> Unit) {

        return replace(StoredProcedure(storedProcedureId, procedure), ResourceLocation.StoredProcedure(databaseId, collectionId, storedProcedureId), callback = callback)
    }

    // replace
    fun replaceStoredProcedure(storedProcedureId: String, procedure: String, collection: DocumentCollection, callback: (Response<StoredProcedure>) -> Unit) {

        return replace(StoredProcedure(storedProcedureId, procedure), ResourceLocation.Child(ResourceType.StoredProcedure, collection, storedProcedureId), callback = callback)
    }

    // execute
    fun executeStoredProcedure(storedProcedureId: String, parameters: List<String>?, collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return execute(parameters, ResourceLocation.StoredProcedure(databaseId, collectionId, storedProcedureId), callback)
    }

    // execute
    fun executeStoredProcedure(storedProcedureId: String, parameters: List<String>?, collection: DocumentCollection, callback: (DataResponse) -> Unit) {

        return execute(parameters, ResourceLocation.Child(ResourceType.StoredProcedure, collection, storedProcedureId), callback)
    }

    //endregion

    //region User Defined Functions

    // create
    fun createUserDefinedFunction(userDefinedFunctionId: String, functionBody: String, collectionId: String, databaseId: String, callback: (Response<UserDefinedFunction>) -> Unit) {

        return create(UserDefinedFunction(userDefinedFunctionId, functionBody), ResourceLocation.Udf(databaseId, collectionId), callback = callback)
    }

    // create
    fun createUserDefinedFunction(userDefinedFunctionId: String, functionBody: String, collection: DocumentCollection, callback: (Response<UserDefinedFunction>) -> Unit) {

        return create(UserDefinedFunction(userDefinedFunctionId, functionBody), ResourceLocation.Child(ResourceType.Udf, collection), callback = callback)
    }

    // list
    fun getUserDefinedFunctions(collectionId: String, databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<UserDefinedFunction>) -> Unit) {

        return resources(ResourceLocation.Udf(databaseId, collectionId), callback, maxPerPage = maxPerPage, resourceClass = UserDefinedFunction::class.java)
    }

    // list
    fun getUserDefinedFunctions(collection: DocumentCollection, maxPerPage: Int? = null, callback: (ListResponse<UserDefinedFunction>) -> Unit) {

        return resources(ResourceLocation.Child(ResourceType.Udf, collection), callback, maxPerPage = maxPerPage, resourceClass = UserDefinedFunction::class.java)
    }

    // delete
    fun deleteUserDefinedFunction(userDefinedFunctionId: String, collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Udf(databaseId, collectionId, userDefinedFunctionId), callback)
    }

    // delete
    fun deleteUserDefinedFunction(userDefinedFunctionId: String, collection: DocumentCollection, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Child(ResourceType.Udf, collection, userDefinedFunctionId), callback)
    }

    // replace
    fun replaceUserDefinedFunction(userDefinedFunctionId: String, function: String, collectionId: String, databaseId: String, callback: (Response<UserDefinedFunction>) -> Unit) {

        return replace(UserDefinedFunction(userDefinedFunctionId, function), ResourceLocation.Udf(databaseId, collectionId, userDefinedFunctionId), callback = callback)
    }

    // replace
    fun replaceUserDefinedFunction(userDefinedFunctionId: String, function: String, collection: DocumentCollection, callback: (Response<UserDefinedFunction>) -> Unit) {

        return replace(UserDefinedFunction(userDefinedFunctionId, function), ResourceLocation.Child(ResourceType.Udf, collection, userDefinedFunctionId), callback = callback)
    }

    //endregion

    //region Triggers

    // create
    fun createTrigger(triggerId: String, operation: Trigger.TriggerOperation, triggerType: Trigger.TriggerType, triggerBody: String, collectionId: String, databaseId: String, callback: (Response<Trigger>) -> Unit) {

        return create(Trigger(triggerId, triggerBody, operation, triggerType), ResourceLocation.Trigger(databaseId, collectionId), callback = callback)
    }

    // create
    fun createTrigger(triggerId: String, operation: Trigger.TriggerOperation, triggerType: Trigger.TriggerType, triggerBody: String, collection: DocumentCollection, callback: (Response<Trigger>) -> Unit) {

        return create(Trigger(triggerId, triggerBody, operation, triggerType), ResourceLocation.Child(ResourceType.Trigger, collection), callback = callback)
    }

    // list
    fun getTriggers(collectionId: String, databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<Trigger>) -> Unit) {

        return resources(ResourceLocation.Trigger(databaseId, collectionId), callback, maxPerPage = maxPerPage, resourceClass = Trigger::class.java)
    }

    // list
    fun getTriggers(collection: DocumentCollection, maxPerPage: Int? = null, callback: (ListResponse<Trigger>) -> Unit) {

        return resources(ResourceLocation.Child(ResourceType.Trigger, collection), callback, maxPerPage = maxPerPage, resourceClass = Trigger::class.java)
    }

    // delete
    fun deleteTrigger(triggerId: String, collectionId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Trigger(databaseId, collectionId, triggerId), callback)
    }

    // delete
    fun deleteTrigger(triggerId: String, collection: DocumentCollection, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Child(ResourceType.Trigger, collection, triggerId), callback)
    }

    // replace
    fun replaceTrigger(triggerId: String, operation: Trigger.TriggerOperation, triggerType: Trigger.TriggerType, triggerBody: String, collectionId: String, databaseId: String, callback: (Response<Trigger>) -> Unit) {

        return replace(Trigger(triggerId, triggerBody, operation, triggerType), ResourceLocation.Trigger(databaseId, collectionId, triggerId), callback = callback)
    }

    // replace
    fun replaceTrigger(triggerId: String, operation: Trigger.TriggerOperation, triggerType: Trigger.TriggerType, triggerBody: String, collection: DocumentCollection, callback: (Response<Trigger>) -> Unit) {

        return replace(Trigger(triggerId, triggerBody, operation, triggerType), ResourceLocation.Child(ResourceType.Trigger, collection, triggerId), callback = callback)
    }

    //endregion

    //region Users

    // create
    fun createUser(userId: String, databaseId: String, callback: (Response<User>) -> Unit) {

        return create(User(userId), ResourceLocation.User(databaseId), callback = callback)
    }

    // list
    fun getUsers(databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<User>) -> Unit) {

        return resources(ResourceLocation.User(databaseId), callback, maxPerPage = maxPerPage, resourceClass = User::class.java)
    }

    // get
    fun getUser(userId: String, databaseId: String, callback: (Response<User>) -> Unit) {

        return resource(ResourceLocation.User(databaseId, userId), callback, resourceClass = User::class.java)
    }

    // delete
    fun deleteUser(userId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.User(databaseId, userId), callback)
    }

    // replace
    fun replaceUser(userId: String, newUserId: String, databaseId: String, callback: (Response<User>) -> Unit) {

        return replace(User(newUserId), ResourceLocation.User(databaseId, userId), callback = callback)
    }

    //endregion

    //region Permissions

    // create
    fun createPermission(permissionId: String, permissionMode: PermissionMode, resource: Resource, userId: String, databaseId: String, callback: (Response<Permission>) -> Unit) {

        val permission = Permission(permissionId, permissionMode, resource.selfLink!!)

        return create(permission, ResourceLocation.Permission(databaseId, userId), callback = callback)
    }

    // create
    fun createPermission(permissionId: String, permissionMode: PermissionMode, resource: Resource, user: User, callback: (Response<Permission>) -> Unit) {

        val permission = Permission(permissionId, permissionMode, resource.selfLink!!)

        return create(permission, ResourceLocation.Child(ResourceType.Permission, user), callback = callback)
    }

    // list
    fun getPermissions(userId: String, databaseId: String, maxPerPage: Int? = null, callback: (ListResponse<Permission>) -> Unit) {

        return resources(ResourceLocation.Permission(databaseId, userId), callback, maxPerPage = maxPerPage, resourceClass = Permission::class.java)
    }

    // list
    fun getPermissions(user: User, maxPerPage: Int? = null, callback: (ListResponse<Permission>) -> Unit) {

        return resources(ResourceLocation.Child(ResourceType.Permission, user), callback, maxPerPage = maxPerPage, resourceClass = Permission::class.java)
    }

    // get
    fun getPermission(permissionId: String, userId: String, databaseId: String, callback: (Response<Permission>) -> Unit) {

        return resource(ResourceLocation.Permission(databaseId, userId, permissionId), callback, resourceClass = Permission::class.java)
    }

    // get
    fun getPermission(permissionId: String, user: User, callback: (Response<Permission>) -> Unit) {

        return resource(ResourceLocation.Child(ResourceType.Permission, user, permissionId), callback, resourceClass = Permission::class.java)
    }

    // delete
    fun deletePermission(permissionId: String, userId: String, databaseId: String, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Permission(databaseId, userId, permissionId), callback)
    }

    // delete
    fun deletePermission(permissionId: String, user: User, callback: (DataResponse) -> Unit) {

        return delete(ResourceLocation.Child(ResourceType.Permission, user, permissionId), callback)
    }

    // replace
    fun replacePermission(permissionId: String, permissionMode: PermissionMode, resourceSelfLink: String, userId: String, databaseId: String, callback: (Response<Permission>) -> Unit) {

        return replace(Permission(permissionId, permissionMode, resourceSelfLink), ResourceLocation.Permission(databaseId, userId, permissionId), callback = callback)
    }

    // replace
    fun replacePermission(permissionId: String, permissionMode: PermissionMode, resourceSelfLink: String, user: User, callback: (Response<Permission>) -> Unit) {

        return replace(Permission(permissionId, permissionMode, resourceSelfLink), ResourceLocation.Child(ResourceType.Permission, user, permissionId), callback = callback)
    }

    //endregion

    //region Offers

    // list
    fun getOffers(maxPerPage: Int? = null, callback: (ListResponse<Offer>) -> Unit) {

        return resources(ResourceLocation.Offer(), callback, maxPerPage = maxPerPage, resourceClass = Offer::class.java)
    }

    // get
    fun getOffer(offerId: String, callback: (Response<Offer>) -> Unit): Any {

        return resource(ResourceLocation.Offer(offerId), callback, resourceClass = Offer::class.java)
    }

    //endregion

    //region Resource operations

    // create
    private fun <T : Resource> create(resource: T, resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, replace: Boolean = false, callback: (Response<T>) -> Unit) {

        if (!resource.hasValidId()) {
            return callback(Response(DataError(DocumentClientError.InvalidId)))
        }

        createOrReplace(resource, resourceLocation, replace, additionalHeaders, callback)
    }

    // list
    private fun <T : Resource> resources(resourceLocation: ResourceLocation, callback: (ListResponse<T>) -> Unit, resourceClass: Class<T>? = null, maxPerPage: Int? = null) {

        createRequest(HttpMethod.Get, resourceLocation, maxPerPage = maxPerPage) {

            sendResourceListRequest(
                request = it,
                resourceLocation = resourceLocation,
                callback = { response ->
                    processResourceListResponse(
                        resourceLocation = resourceLocation,
                        response = response,
                        callback = callback,
                        resourceClass = resourceClass
                    )
                },
                resourceClass = resourceClass
            )
        }
    }

    // get
    private fun <T : Resource> resource(resourceLocation: ResourceLocation, callback: (Response<T>) -> Unit, resourceClass: Class<T>? = null) {

        createRequest(HttpMethod.Get, resourceLocation) {

            sendResourceRequest(
                    request = it,
                    resourceLocation = resourceLocation,
                    callback = { response ->
                        processResourceGetResponse(
                            resourceLocation = resourceLocation,
                            response = response,
                            callback = callback,
                            resourceClass = resourceClass
                        )
                    },
                    resourceClass = resourceClass
            )
        }
    }

    // refresh
    fun <T : Resource> refresh(resource: T, callback: (Response<T>) -> Unit) {

        return try {

            val resourceLocation = ResourceLocation.Resource(resource)

            // create the request - if we have an etag, we'll set & send the IfNoneMatch header
            if (!resource.etag.isNullOrEmpty()) {

                val headers = Headers.Builder()
                        .add(HttpHeader.IfNoneMatch.value, resource.etag!!)
                        .build()

                createRequest(HttpMethod.Get, resourceLocation, headers) {
                    //send the request!
                    sendResourceRequest(it, resourceLocation, resource, callback)
                }
            } else {

                createRequest(HttpMethod.Get, resourceLocation) {
                    //send the request!
                    sendResourceRequest(it, resourceLocation, resource, callback)
                }
            }
        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex)))
        }
    }

    // delete
    internal fun delete(resourceLocation: ResourceLocation, callback: (DataResponse) -> Unit) {

        createRequest(HttpMethod.Delete, resourceLocation) {

            sendRequest(
                request = it,
                resourceLocation = resourceLocation,
                callback = { response: DataResponse ->
                    processDeleteResponse(
                        resourceLocation = resourceLocation,
                        additionalHeaders = null,
                        response = response,
                        callback = callback
                    )
                }
            )
        }
    }

    fun <TResource : Resource> delete(resource: TResource, callback: (DataResponse) -> Unit) {

        return try {

            val resourceLocation = ResourceLocation.Resource(resource)

            createRequest(HttpMethod.Delete, resourceLocation) {

                sendRequest(
                    request = it,
                    resourceLocation = resourceLocation,
                    callback = { response: DataResponse ->
                        processDeleteResponse(
                            resourceLocation = resourceLocation,
                            additionalHeaders = null,
                            response = response,
                            callback = callback
                        )
                    }
                )
            }

        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex)))
        }
    }

    // replace
    private fun <T : Resource> replace(resource: T, resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, callback: (Response<T>) -> Unit) {

        if (!resource.hasValidId()) {
            return callback(Response(DataError(DocumentClientError.InvalidId)))
        }

        createOrReplace(resource, resourceLocation, true, additionalHeaders, callback)
    }

    // create or replace
    internal fun <T : Resource> createOrReplace(body: T, resourceLocation: ResourceLocation, replacing: Boolean = false, additionalHeaders: Headers? = null, callback: (Response<T>) -> Unit) {

        try {
            val jsonBody = gson.toJson(body)
            var headers = additionalHeaders

            if (replacing) {
                val builder = headers?.newBuilder() ?: Headers.Builder()
                builder.add(MSHttpHeader.MSDocumentDBIsUpsert.value,"true")
                headers = builder.build()
            }

            createRequest(if (replacing) HttpMethod.Put else HttpMethod.Post, resourceLocation, headers, jsonBody) {

                @Suppress("UNCHECKED_CAST")
                sendResourceRequest(
                        request = it,
                        resourceLocation = resourceLocation,
                        callback = { response: Response<T> ->
                            processCreateOrReplaceResponse(
                                 resource = body,
                                 location = resourceLocation,
                                 replace = replacing,
                                 additionalHeaders = additionalHeaders,
                                 response = response,
                                 callback = callback
                            )
                        },
                        resourceClass = body::class.java as Class<T>
                )
            }

        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex)))
        }
    }

    // create or replace
    private fun <T : Resource> createOrReplace(body: ByteArray, resourceLocation: ResourceLocation, replacing: Boolean = false, additionalHeaders: Headers? = null, callback: (Response<T>) -> Unit, resourceClass: Class<T>? = null) {

        try {
            createRequest(if (replacing) HttpMethod.Put else HttpMethod.Post, resourceLocation, additionalHeaders, body) {

                sendResourceRequest(it, resourceLocation, callback, resourceClass)
            }
        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex)))
        }
    }

    // query
    private fun <T : Resource> query(query: Query, resourceLocation: ResourceLocation, maxPerPage: Int?, callback: (ListResponse<T>) -> Unit, resourceClass: Class<T>? = null) {

        d{query.toString()}

        try {
            val json = gson.toJson(query.dictionary)

            createRequest(HttpMethod.Post, resourceLocation, forQuery = true, jsonBody = json, maxPerPage = maxPerPage) {

                sendResourceListRequest(it, resourceLocation, callback, resourceClass)
            }
        } catch (ex: Exception) {
            e(ex)
            callback(ListResponse(DataError(ex)))
        }
    }

    // next
    fun <T : Resource> next(response : ListResponse<T>, resourceType: Type?, callback: (ListResponse<T>) -> Unit) {

        try {
            val request = response.request
                ?: return callback(ListResponse(DataError(DocumentClientError.NextCalledTooEarlyError)))

            val resourceLocation = response.resourceLocation
                ?: return callback(ListResponse(DataError(DocumentClientError.NextCalledTooEarlyError)))

            val type = resourceType
                    ?: return callback(ListResponse(DataError(DocumentClientError.NextCalledTooEarlyError)))

            val continuation = response.metadata.continuation
                ?: return callback(ListResponse(DataError(DocumentClientError.NoMoreResultsError)))

            val newRequest = request.newBuilder()
                    .header(MSHttpHeader.MSContinuation.value,continuation)
                    .build()

            client.newCall(newRequest)
                    .enqueue(object : Callback {

                        // only transport errors handled here
                        override fun onFailure(call: Call, e: IOException) {
                            isOffline = true
                            // todo: callback with cached data instead of the callback with the error below
                            callback(Response(DataError(e)))
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: Call, resp: okhttp3.Response)  =
                                callback(processListResponse(request, resp, resourceLocation, type))

                    })
        } catch (ex: Exception) {
            e(ex)
            callback(ListResponse(DataError(ex)))
        }
    }

    // execute
    private fun <T> execute(body: T? = null, resourceLocation: ResourceLocation, callback: (DataResponse) -> Unit) {

        try {
            val json = if (body != null) gson.toJson(body) else gson.toJson(arrayOf<String>())

            createRequest(HttpMethod.Post, resourceLocation, jsonBody = json) {

                sendRequest(it, resourceLocation, callback)
            }

        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex)))
        }
    }

    //endregion

    //region Network plumbing

    private val dateFormatter : SimpleDateFormat by lazy {
        DateUtil.getDateFromatter(DateUtil.Format.Rfc1123Format)
    }

    private inline fun getTokenForResource(resourceLocation: ResourceLocation, method: HttpMethod, crossinline callback: (Response<ResourceToken>) -> Unit) {

        if (!isConfigured) {
            return callback(Response(DataError(DocumentClientError.ConfigureError)))
        }

        if (resourceLocation.id?.isValidIdForResource() == false) {
            return callback(Response(DataError(DocumentClientError.InvalidId)))
        }

        if (resourceTokenProvider != null) {

            resourceTokenProvider!!.getToken(resourceLocation, method)?.let {
                return callback(Response(it))
            }
        } else {

            if (!resourceLocation.supportsPermissionToken) {
                return callback(Response(DataError(DocumentClientError.PermissionError)))
            }

            return permissionProvider?.getPermission(resourceLocation, if (method.isWrite()) PermissionMode.All else PermissionMode.Read) {

                if (it.isSuccessful) {

                    val dateString = String.format("%s %s", dateFormatter.format(Date()), "GMT")

                    it.resource?.token?.let {

                        callback(Response(ResourceToken(URLEncoder.encode(it, "UTF-8"), dateString)))

                    } ?: callback(Response(DataError(DocumentClientError.PermissionError)))
                } else {
                    callback(Response(it.error!!))
                }
            } ?: callback(Response(DataError(DocumentClientError.UnknownError)))
        }

        return callback(Response(DataError(DocumentClientError.UnknownError)))
    }

    private inline fun createRequest(method: HttpMethod, resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, maxPerPage: Int? = null, crossinline callback: (Request) -> Unit) {

        createRequestBuilder(method, resourceLocation, additionalHeaders, maxPerPage) {

            when (method) {
                HttpMethod.Get -> it.get()
                HttpMethod.Head -> it.head()
                HttpMethod.Delete -> it.delete()
                else -> throw Exception("Post and Put requests must use an overload that provides the content body")
            }

            callback(it.build())
        }
    }

    private inline fun createRequest(method: HttpMethod, resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, jsonBody: String, forQuery: Boolean = false, maxPerPage: Int? = null, crossinline callback: (Request) -> Unit) {

        createRequestBuilder(method, resourceLocation, additionalHeaders, maxPerPage) {

            // For Post on query operations, it must be application/query+json
            // For attachments, must be set to the Mime type of the attachment.
            // For all other tasks, must be application/json.
            var mediaType = jsonMediaType

            if (forQuery) {
                it.addHeader(MSHttpHeader.MSDocumentDBIsQuery.value, "True")
                it.addHeader(HttpHeader.ContentType.value, HttpMediaType.QueryJson.value)
                mediaType = MediaType.parse(HttpMediaType.QueryJson.value)
            }
            else if ((method == HttpMethod.Post || method == HttpMethod.Put) && resourceLocation.resourceType != ResourceType.Attachment) {

                it.addHeader(HttpHeader.ContentType.value, HttpMediaType.Json.value)
            }

            // we convert the json to bytes here rather than allowing OkHttp, as they will tack on
            //  a charset string that does not work well with certain operations (Query)
            val body = jsonBody.toByteArray(Charsets.UTF_8)

            when (method) {
                HttpMethod.Post -> it.post(RequestBody.create(mediaType, body))
                HttpMethod.Put -> it.put(RequestBody.create(mediaType, body))
                else -> throw Exception("Get, Head, and Delete requests must use an overload that without a content body")
            }

            callback(it.build())
        }
    }

    private inline fun createRequest(method: HttpMethod, resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, body: ByteArray, maxPerPage: Int? = null, crossinline callback: (Request) -> Unit) {

        createRequestBuilder(method, resourceLocation, additionalHeaders, maxPerPage) {

            var mediaType = jsonMediaType

            additionalHeaders?.get(HttpHeader.ContentType.value)?.let {
                mediaType = MediaType.parse(it)
            }

            when (method) {
                HttpMethod.Post -> it.post(RequestBody.create(mediaType, body))
                HttpMethod.Put -> it.put(RequestBody.create(mediaType, body))
                else -> throw Exception("Get, Head, and Delete requests must use an overload that without a content body")
            }

            callback(it.build())
        }
    }

    private inline fun createRequestBuilder(method: HttpMethod, resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, maxPerPage: Int? = null, crossinline callback: (Request.Builder) -> Unit) {

        getTokenForResource(resourceLocation, method) {

            when {
                it.isSuccessful -> it.resource?.let {

                    val url = HttpUrl.Builder()
                            .scheme("https")
                            .host(this.host!!)
                            .addPathSegment(resourceLocation.path())
                            .build()

                    val builder = Request.Builder()
                            .headers(headers) //base headers
                            .url(url)

                    // set the api version
                    builder.addHeader(MSHttpHeader.MSVersion.value, HttpHeaderValue.apiVersion)
                    // and the token data
                    builder.addHeader(MSHttpHeader.MSDate.value, it.date)
                    builder.addHeader(HttpHeader.Authorization.value, it.token)

                    // add the count
                    maxPerPage?.let {
                        if ((1..1000).contains(it)) {
                            builder.addHeader(MSHttpHeader.MSMaxItemCount.value, it.toString())
                        } else {
                            throw DocumentClientError.InvalidMaxPerPageError
                        }
                    }

                    // if we have additional headers, let's add them in here
                    additionalHeaders?.let {
                        for (headerName in additionalHeaders.names()) {
                            builder.addHeader(headerName, additionalHeaders[headerName]!!)
                        }
                    }

                    callback(builder)

                } ?: throw DocumentClientError.UnknownError

                it.isErrored -> throw it.error!!

                else -> throw DocumentClientError.UnknownError
            }
        }
    }

    private inline fun <T : Resource> sendResourceRequest(request: Request, resourceLocation: ResourceLocation, crossinline callback: (Response<T>) -> Unit, resourceClass: Class<T>? = null)
            = sendResourceRequest(request, resourceLocation, null, callback = callback, resourceClass = resourceClass)

    private inline fun <T : Resource> sendResourceRequest(request: Request, resourceLocation: ResourceLocation, resource: T?, crossinline callback: (Response<T>) -> Unit, resourceClass: Class<T>? = null) {

        d{"***"}
        d{"Sending ${request.method()} request for Data to ${request.url()}"}
        d{"\tContent : length = ${request.body()?.contentLength()}, type = ${request.body()?.contentType()}"}
        d{"***"}

        try {
            client.newCall(request)
                    .enqueue(object : Callback {

                        override fun onFailure(call: Call, ex: IOException) {
                            e(ex)
                            isOffline = true

                            callback(Response(error = DataError(DocumentClientError.InternetConnectivityError)))
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: Call, response: okhttp3.Response) =
                                callback(processResponse(request, response, resourceLocation.resourceType, resource, resourceClass))
                    })
        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex), request))
        }
    }

    private inline fun sendRequest(request: Request, resourceLocation: ResourceLocation, crossinline callback: (DataResponse) -> Unit) {

        d{"***"}
        d{"Sending ${request.method()} request for Data to ${request.url()}"}
        d{"\tContent : length = ${request.body()?.contentLength()}, type = ${request.body()?.contentType()}"}
        d{"***"}

        try {
            client.newCall(request)
                    .enqueue(object : Callback {

                        override fun onFailure(call: Call, ex: IOException) {
                            e(ex)
                            isOffline = true

                            return callback(Response(error = DataError(DocumentClientError.InternetConnectivityError)))
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: Call, response: okhttp3.Response) =
                                callback(processDataResponse(request, resourceLocation, response))
                    })
        } catch (ex: Exception) {
            e(ex)
            callback(Response(DataError(ex), request))
        }
    }

    private inline fun <T : Resource> sendResourceListRequest(request: Request, resourceLocation: ResourceLocation, crossinline callback: (ListResponse<T>) -> Unit, resourceClass: Class<T>? = null) {

        d{"***"}
        d{"Sending ${request.method()} request for Data to ${request.url()}"}
        d{"\tContent : length = ${request.body()?.contentLength()}, type = ${request.body()?.contentType()}"}
        d{"***"}

        try {
            client.newCall(request)
                    .enqueue(object : Callback {

                        // only transport errors handled here
                        override fun onFailure(call: Call, e: IOException) {
                            isOffline = true

                            callback(ListResponse(DataError(DocumentClientError.InternetConnectivityError)))
                        }

                        @Throws(IOException::class)
                        override fun onResponse(call: Call, response: okhttp3.Response) =
                                callback(processListResponse(request, response, resourceLocation, resourceClass))
                    })
        } catch (ex: Exception) {
            e(ex)
            callback(ListResponse(DataError(ex), request))
        }
    }

    private fun <T : Resource> processResponse(request: Request, response: okhttp3.Response, resourceType: ResourceType, resource: T?, resourceClass: Class<T>? = null): Response<T> {

        try {
            val body = response.body()
                    ?: return Response(DataError("Empty response body received"))
            val json = body.string().also{d{it}}

            //check http return code/success
            when {
            // HttpStatusCode.Created: // cache locally
            // HttpStatusCode.NoContent: // DELETEing a resource remotely should delete the cached version (if the delete was successful indicated by a response status code of 204 No Content)
            // HttpStatusCode.Unauthorized:
            // HttpStatusCode.Forbidden: // reauth
            // HttpStatusCode.Conflict: // conflict callback
            // HttpStatusCode.NotFound: // (indicating the resource has been deleted/no longer exists in the remote database), confirm that resource does not exist locally, and if it does, delete it
            // HttpStatusCode.PreconditionFailure: // The operation specified an eTag that is different from the version available at the server, that is, an optimistic concurrency error. Retry the request after reading the latest version of the resource and updating the eTag on the request.

                response.isSuccessful -> {

                    val type = resourceClass ?: resource?.javaClass ?: resourceType.type
                    val returnedResource = gson.fromJson<T>(json, type)
                            ?: return Response(json.toError())

                    setResourceMetadata(response, returnedResource, resourceType)

                    return Response(request, response, json, Result(returnedResource))
                }

                response.code() == HttpStatusCode.NotModified.code -> {

                    resource?.let {
                        setResourceMetadata(response, it, resourceType)
                    }

                    //return the original resource
                    return Response(request, response, json, Result(resource))
                }

                else -> return Response(json.toError(), request, response, json)
            }
        } catch (e: Exception) {
            return Response(DataError(e), request, response)
        }
    }

    private fun <T : Resource> processListResponse(request: Request, response: okhttp3.Response, resourceLocation: ResourceLocation, resourceType: Type? = null): ListResponse<T> {

        try {
            val body = response.body()
                    ?: return ListResponse(DataError("Empty response body received"), request, response)
            val json = body.string().also{d{it}}

            if (response.isSuccessful) {

                //TODO: see if there's any benefit to caching these type tokens performance wise (or for any other reason)
                val type = resourceType ?: resourceLocation.resourceType.type
                val resourceList = ResourceListJsonDeserializer<T>().deserialize(json, type)

                setResourceMetadata(response, resourceList, resourceLocation.resourceType)

                ResourceCache.shared.cache(resourceList)

                return ListResponse(request, response, json, Result(resourceList), resourceLocation, type)
            } else {
                return ListResponse(json.toError(), request, response, json)
            }
        } catch (e: Exception) {
            return ListResponse(DataError(e), request, response)
        }
    }

    private fun <T: Resource> processCreateOrReplaceResponse(resource: T, location: ResourceLocation, replace: Boolean, additionalHeaders: Headers? = null, response: Response<T>, callback: (Response<T>) -> Unit) {
        when {
            response.isSuccessful -> {
                callback(response)

                when (replace) {
                    true  -> response.resource?.let { ResourceCache.shared.replace(it) }
                    false -> response.resource?.let { ResourceCache.shared.cache(it) }
                }
            }

            response.isErrored -> {
                if (response.error!!.isConnectivityError()) {
                    ResourceWriteOperationQueue.shared.addCreateOrReplace(resource, location, additionalHeaders, replace, callback)
                    return
                }

                callback(response)
            }

            else -> { callback(response) }
        }
    }

    private fun <T: Resource> processResourceGetResponse(resourceLocation: ResourceLocation, response: Response<T>, callback: (Response<T>) -> Unit, resourceClass: Class<T>?) {
        when {
            response.isSuccessful -> {
                callback(response)

                response.resource?.let { ResourceCache.shared.cache(it) }
            }

            response.isErrored -> {
                if (response.error!!.isConnectivityError() && resourceClass != null)  {
                    cachedResource(resourceLocation, response, callback, resourceClass)
                    return
                }

                if (response.is404()) {
                    ResourceCache.shared.remove(resourceLocation)
                }

                callback(response)
            }

            else -> { callback(response) }
        }
    }

    private fun <T: Resource> processResourceListResponse(resourceLocation: ResourceLocation, response: ListResponse<T>, callback: (ListResponse<T>) -> Unit, resourceClass: Class<T>?) {
        when {
            response.isSuccessful -> {
                callback(response)

                response.resource?.let { ResourceCache.shared.cache(it) }
            }

            response.isErrored -> {
                if (response.error!!.isConnectivityError() && resourceClass != null) {
                    cachedResources(resourceLocation, response, callback, resourceClass)
                    return
                }

                callback(response)
            }

            else -> { callback(response) }
        }
    }

    private fun processDeleteResponse(resourceLocation: ResourceLocation, additionalHeaders: Headers? = null, response: DataResponse, callback: (DataResponse) -> Unit) {
        when {
            response.isSuccessful -> {
                callback(response)

                ResourceCache.shared.remove(resourceLocation)
            }

            response.isErrored -> {
                if (response.error!!.isConnectivityError()) {
                    ResourceWriteOperationQueue.shared.addDelete(resourceLocation, additionalHeaders, callback)
                    return
                }

                callback(response)
            }
        }
    }

    private fun setResourceMetadata(response: okhttp3.Response, resource: ResourceBase, resourceType: ResourceType) {

        //grab & store alt Link and persist alt link <-> self link mapping
        val altContentPath = response.header(MSHttpHeader.MSAltContentPath.value, null)
        resource.setAltContentLink(resourceType.path, altContentPath)
        ResourceOracle.shared.storeLinks(resource)
    }

    private fun processDataResponse(request: Request, resourceLocation: ResourceLocation, response: okhttp3.Response): DataResponse {

        try {
            val body = response.body()
                    ?: return Response(DataError("Empty response body received"), request, response)
            val responseBodyString = body.string().also{d{it}}

            //check http return code
            return if (response.isSuccessful) {

                if (request.method() == HttpMethod.Delete.toString()) {
                    ResourceCache.shared.remove(resourceLocation)
                }

                DataResponse(request, response, responseBodyString, Result(responseBodyString))
            } else {
                Response(responseBodyString.toError(), request, response, responseBodyString)
            }
        } catch (e: Exception) {
            return Response(DataError(e), request, response)
        }
    }

    //endregion

    //region Cache Responses

    private fun <T: Resource> cachedResource(resourceLocation: ResourceLocation, response: Response<T>? = null, callback: (Response<T>) -> Unit, resourceClass: Class<T>) {
        ResourceCache.shared.getResourceAt(resourceLocation, resourceClass)?.let {
            callback(Response(response?.request, response?.response, response?.jsonData, Result(resource = it), resourceLocation, response?.resourceType, fromCache = true))
            return
        }

        callback(Response(response?.request, response?.response, response?.jsonData, Result(error = DataError(DocumentClientError.NotFound)), fromCache = true))
    }

    private fun <T: Resource> cachedResources(resourceLocation: ResourceLocation, response: ListResponse<T>? = null, callback: (ListResponse<T>) -> Unit, resourceClass: Class<T>) {
        val resources = ResourceCache.shared.getResourcesAt(resourceLocation, resourceClass)

        callback(Response(response?.request, response?.response, response?.jsonData, Result(resources), resourceLocation, response?.resourceType, fromCache = true))
    }

    //endregion

    companion object {

        val shared = DocumentClient()

        var client = OkHttpClient()

        val jsonMediaType = MediaType.parse(HttpMediaType.Json.value)
    }
}
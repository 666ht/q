package io.anonero.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import io.anonero.model.node.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

const val NODE_STORE = "nodes_store"
val jsonDecoder = Json { ignoreUnknownKeys = true }
private const val TAG = "NodeDataStore"

object NodeListSerializer : Serializer<List<Node>> {

    override val defaultValue: List<Node> = emptyList()

    override suspend fun readFrom(input: InputStream): List<Node> {
        return try {
            val json = input.readBytes().decodeToString()
            jsonDecoder.decodeFromString(ListSerializer(Node.serializer()), json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun writeTo(t: List<Node>, output: OutputStream) {
        val json = jsonDecoder.encodeToString(ListSerializer(Node.serializer()), t)
        withContext(Dispatchers.IO) {
            output.write(json.encodeToByteArray())
        }
    }
}


private val Context.nodeDataStore: DataStore<List<Node>> by dataStore(
    fileName = NODE_STORE,
    serializer = NodeListSerializer
)


class NodesRepository(private val context: Context) {

    // Expose a Flow to observe the list of items
    val nodesFlow: Flow<List<Node>> = context.nodeDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyList()) // Handle errors by emitting an empty list
            } else {
                throw exception
            }
        }

    // Save a list of items
    suspend fun saveItems(items: List<Node>) {
        context.nodeDataStore.updateData { items }
    }

    // Add a new item to the list
    suspend fun addItem(item: Node) {
        context.nodeDataStore.updateData { currentItems ->
            if (currentItems.find { it.toNodeString() == item.toNodeString() } == null) {
                currentItems + item
            } else {
                currentItems
            }
        }
    }

    suspend fun addItemFirst(item: Node) {
        context.nodeDataStore.updateData { currentItems ->
            if (currentItems.find { it.toNodeString() == item.toNodeString() } == null) {
                listOf(item) + currentItems
            } else {
                currentItems
            }
        }
    }


    // Add a new item to the list
    suspend fun getAll() : List<Node> {
        try {
            return context.nodeDataStore.data.first();
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // Remove an item by NodeString
    suspend fun removeItemByNodeString(nodeString: String) {
        context.nodeDataStore.updateData { currentItems ->
            currentItems.filter {
                it.toNodeString() != nodeString
            }
        }
    }

    suspend fun clearAll() {
        context.nodeDataStore.updateData {
            emptyList()
        }
    }
}
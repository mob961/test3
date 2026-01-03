package ai.restorank.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class PrintService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var printerManager: PrinterManager
    private var isPolling = false
    private val processedOrderIds = mutableSetOf<String>()

    companion object {
        private const val TAG = "PrintService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "print_service_channel"
        
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        printerManager = PrinterManager(this)
        createNotificationChannel()
        isRunning = true
        Log.d(TAG, "PrintService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (!isPolling && printerManager.isAutoPrintEnabled()) {
            startPolling()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        isRunning = false
        executor.shutdown()
        Log.d(TAG, "PrintService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Print Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for automatic order printing"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RestoRank Auto-Print")
            .setContentText("Monitoring for new orders...")
            .setSmallIcon(R.drawable.ic_printer)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startPolling() {
        isPolling = true
        
        // Sync printers from server on start
        executor.execute {
            printerManager.syncPrintersFromServer()
        }
        
        pollForOrders()
    }

    private var syncCounter = 0
    
    private fun pollForOrders() {
        if (!isPolling) return
        
        executor.execute {
            try {
                // Sync printers from server every 10 polls (~50 seconds)
                syncCounter++
                if (syncCounter >= 10) {
                    syncCounter = 0
                    printerManager.syncPrintersFromServer()
                }
                
                val orders = fetchNewOrders()
                orders.forEach { order ->
                    val orderId = order.optString("id")
                    if (orderId.isNotEmpty() && !processedOrderIds.contains(orderId)) {
                        processedOrderIds.add(orderId)
                        printOrder(order)
                        printerManager.setLastOrderId(orderId)
                        
                        // Keep set from growing too large (max 100 orders)
                        if (processedOrderIds.size > 100) {
                            processedOrderIds.remove(processedOrderIds.first())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling orders: ${e.message}")
            }

            // Schedule next poll
            handler.postDelayed({
                if (isPolling && printerManager.isAutoPrintEnabled()) {
                    pollForOrders()
                }
            }, printerManager.getPollInterval())
        }
    }

    private fun fetchNewOrders(): List<JSONObject> {
        val baseUrl = printerManager.getServerUrl()
        val restaurantId = printerManager.getRestaurantId()
        
        return try {
            val url = URL("$baseUrl/api/restaurants/$restaurantId/orders/pending-print")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()
                
                val array = JSONArray(response)
                (0 until array.length()).map { array.getJSONObject(it) }
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch orders: ${e.message}")
            emptyList()
        }
    }

    private fun markOrderAsPrinted(orderId: String) {
        val baseUrl = printerManager.getServerUrl()
        
        try {
            val url = URL("$baseUrl/api/orders/$orderId/printed")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val output = OutputStreamWriter(conn.outputStream)
            output.write("{}")
            output.flush()
            output.close()
            
            val responseCode = conn.responseCode
            conn.disconnect()
            
            if (responseCode == 200) {
                Log.d(TAG, "Order $orderId marked as printed")
            } else {
                Log.e(TAG, "Failed to mark order as printed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark order as printed: ${e.message}")
        }
    }

    private fun printOrder(order: JSONObject) {
        val printers = printerManager.getPrinters().filter { it.isEnabled }
        if (printers.isEmpty()) {
            Log.w(TAG, "No enabled printers configured")
            return
        }

        val orderId = order.optString("id", "")
        val receipt = formatReceipt(order)
        var printedSuccessfully = false
        
        printers.forEach { printer ->
            try {
                printToThermal(printer.ip, printer.port, receipt)
                Log.d(TAG, "Printed to ${printer.name}")
                printedSuccessfully = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to print to ${printer.name}: ${e.message}")
            }
        }
        
        // Mark order as printed on the server
        if (printedSuccessfully && orderId.isNotEmpty()) {
            markOrderAsPrinted(orderId)
        }
    }

    private fun formatReceipt(order: JSONObject): ByteArray {
        val orderId = order.optString("id", "N/A").takeLast(6)
        val orderType = order.optString("type", "Dine-in")
        val tableName = order.optString("tableName", "")
        val items = order.optJSONArray("items") ?: JSONArray()
        val total = order.optDouble("total", 0.0)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val sb = StringBuilder()
        
        // ESC/POS commands
        val ESC = 0x1B.toChar()
        val GS = 0x1D.toChar()
        
        // Initialize printer
        sb.append("${ESC}@")
        
        // Center align
        sb.append("${ESC}a${1.toChar()}")
        
        // Bold on, double height
        sb.append("${ESC}E${1.toChar()}")
        sb.append("${GS}!${0x10.toChar()}")
        sb.append("RESTORANK\n")
        sb.append("${GS}!${0.toChar()}")
        sb.append("Kitchen Ticket\n")
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Left align
        sb.append("${ESC}a${0.toChar()}")
        
        sb.append("Order: #$orderId\n")
        sb.append("Type: $orderType\n")
        if (tableName.isNotEmpty()) {
            sb.append("Table: $tableName\n")
        }
        sb.append("Time: $time\n")
        
        sb.append("--------------------------------\n")
        
        // Items
        sb.append("${ESC}E${1.toChar()}")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            val price = item.optDouble("price", 0.0)
            sb.append("${qty}x $name\n")
            sb.append("   RM %.2f\n".format(price * qty))
            
            val notes = item.optString("notes", "")
            if (notes.isNotEmpty()) {
                sb.append("   > $notes\n")
            }
        }
        sb.append("${ESC}E${0.toChar()}")
        
        sb.append("================================\n")
        
        // Right align for total
        sb.append("${ESC}a${2.toChar()}")
        sb.append("${ESC}E${1.toChar()}")
        sb.append("TOTAL: RM %.2f\n".format(total))
        sb.append("${ESC}E${0.toChar()}")
        
        // Feed and cut
        sb.append("\n\n\n")
        sb.append("${GS}V${1.toChar()}")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun printToThermal(ip: String, port: Int, data: ByteArray) {
        val socket = Socket()
        try {
            socket.connect(java.net.InetSocketAddress(ip, port), 5000)
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            output.write(data)
            output.flush()
        } finally {
            socket.close()
        }
    }
}

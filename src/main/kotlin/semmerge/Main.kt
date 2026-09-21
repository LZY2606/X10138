package semmerge

import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5221
    var dataDir = "data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--data" -> dataDir = args[++i]
        }
        i++
    }
    val store = Store(File(dataDir))
    val server = Server(store, host, port)
    server.start()
    println("语义配置合并器已启动: http://$host:$port")
    Thread.currentThread().join()
}

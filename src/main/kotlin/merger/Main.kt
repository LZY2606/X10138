package merger

import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5221
    var dataDir = "cm-data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { dataDir = args[++i] }
            else -> {
                System.err.println("未知参数: ${args[i]}")
                System.err.println("用法: --host 127.0.0.1 --port 5221 [--data cm-data]")
                kotlin.system.exitProcess(2)
            }
        }
        i++
    }
    val store = Store(Paths.get(dataDir))
    val server = WebServer(host, port, store)
    server.start()

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    // 保持进程存活
    Thread.currentThread().join()
}

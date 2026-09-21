package semmerge

import semmerge.web.WebServer
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 语义配置合并器 — local-only web app.
 * Usage: run --args='--host 127.0.0.1 --port 5221'
 */
fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5221
    var dataDir = Path.of(".semcfg-data")

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { dataDir = Path.of(args[++i]) }
            "--help", "-h" -> {
                println("语义配置合并器: --host HOST --port PORT [--data DIR]")
                exitProcess(0)
            }
            else -> throw IllegalArgumentException("未知参数: ${args[i]}")
        }
        i++
    }

    val server = WebServer(host, port, dataDir)
    server.start()
    println("语义配置合并器 已启动: http://$host:${server.address().port}")
    println("数据目录: ${dataDir.toAbsolutePath()}")

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

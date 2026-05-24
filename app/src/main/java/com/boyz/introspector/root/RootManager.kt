package com.boyz.introspector.root

import com.topjohnwu.superuser.Shell

object RootManager {

    init {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)
        )
    }

    val isRooted: Boolean
        get() = Shell.isAppGrantedRoot() == true

    fun runCommand(cmd: String): String {
        val result = Shell.cmd(cmd).exec()
        return result.out.joinToString("\n")
    }
}

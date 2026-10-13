package com.svenuks.imsforpixel

import android.os.IBinder

object ImsQueryTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            
            val isubBinder = getServiceMethod.invoke(null, "isub") as IBinder
            val isubStubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
            val isubAsInterface = isubStubClass.getMethod("asInterface", IBinder::class.java)
            val isubService = isubAsInterface.invoke(null, isubBinder)
            
            val iSubClass = Class.forName("com.android.internal.telephony.ISub")
            val getSubIdMethod = iSubClass.getMethod("getSubId", Int::class.javaPrimitiveType)
            getSubIdMethod.isAccessible = true
            
            for (slot in 0..1) {
                val subId = getSubIdMethod.invoke(isubService, slot) as? Int ?: -1
                if (subId != -1) {
                    val isImsRegistered = checkImsRegistered(subId)
                    println("RESULT:$slot:$isImsRegistered")
                } else {
                    println("RESULT:$slot:false")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkImsRegistered(subId: Int): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "phone") as IBinder
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val telephonyService = asInterfaceMethod.invoke(null, binder)
            
            val iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            val method = iTelephonyClass.getMethod("isImsRegistered", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(telephonyService, subId) as Boolean
        } catch (e: Exception) {
            false
        }
    }
}

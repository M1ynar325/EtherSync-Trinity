package com.etherstories.eslink.core.model

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 反射工具 — 缓存方法查找结果，避免重复解析类的方法表。
 * Class.getMethods() 会把这个类所有方法签名里的类型全部解析一遍。
 * 模组的物品类里带着渲染方法，签名指向客户端类，
 * 服务端上 Forge 的 RuntimeDistCleaner 会拒绝加载并抛异常。
 * 一次无所谓，但放在按物品调用的热路径上就是每 tick 几千次异常 + 类加载，主线程直接卡死。
 * 所以按类缓存一次，失败也缓存。
 */
object Reflect {

    private val NONE: Array<Method> = emptyArray()
    private val methodsCache = ConcurrentHashMap<Class<*>, Array<Method>>()
    private val lookupCache = ConcurrentHashMap<String, Any?>()
    private val MISS = Any()

    /**
     * 获取类的所有方法，结果缓存。
     */
    fun methods(type: Class<*>?): Array<Method> {
        if (type == null) return NONE
        val hit = methodsCache[type]
        if (hit != null) return hit
        val found = try {
            type.methods
        } catch (t: Throwable) {
            NONE
        }
        methodsCache[type] = found
        return found
    }

    /**
     * 获取对象所属类的所有方法。
     */
    fun of(target: Any?): Array<Method> {
        return if (target == null) NONE else methods(target.javaClass)
    }

    /**
     * 按名称和参数类型查找方法（缓存）。
     * getMethod 也会解析整张声明方法表，代价和 getMethods 一样，命中和未命中都得缓存。
     */
    @Throws(NoSuchMethodException::class)
    fun method(type: Class<*>?, name: String, vararg params: Class<*>): Method {
        if (type == null) throw NoSuchMethodException(name)
        val id = buildString {
            append(type.name).append('#').append(name)
            for (p in params) append('/').append(p?.name ?: "null")
        }
        val hit = lookupCache[id]
        if (hit === MISS) throw NoSuchMethodException(id)
        if (hit != null) return hit as Method
        val found = resolve(type, name, *params)
        if (found == null) {
            lookupCache[id] = MISS
            throw NoSuchMethodException(id)
        }
        lookupCache[id] = found
        return found
    }

    /**
     * 从最上层祖先往下找。
     * 模组的物品类自己带渲染方法，去解析它的方法表就会触发客户端类加载；
     * 在 net.minecraft.world.item.Item 这种基类上拿到的 Method 一样是虚派发，结果不变。
     */
    private fun resolve(type: Class<*>, name: String, vararg params: Class<*>): Method? {
        val chain = ArrayDeque<Class<*>>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            chain.addFirst(c)
            c = c.superclass
        }
        for (clazz in chain) {
            try {
                return clazz.getMethod(name, *params)
            } catch (_: Throwable) {
                // continue
            }
        }
        return null
    }

    /**
     * 按方法名和参数个数查找方法（不区分参数类型）。
     */
    fun find(type: Class<*>?, name: String, params: Int): Method? {
        if (type == null) return null
        for (m in methods(type)) {
            if (m.name == name && m.parameterCount == params) return m
        }
        return null
    }
}
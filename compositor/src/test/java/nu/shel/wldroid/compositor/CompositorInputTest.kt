package nu.shel.wldroid.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for [CompositorInput].
 *
 * Since CompositorInput delegates to CompositorServer (which requires native libs),
 * these tests verify the API surface and method signatures without invoking JNI.
 */
class CompositorInputTest {

    @Test
    fun `CompositorInput class exists and is public`() {
        val clazz = CompositorInput::class.java
        assertNotNull(clazz)
        assertEquals("CompositorInput", clazz.simpleName)
    }

    @Test
    fun `sendTouchEvent method exists with correct parameter types`() {
        val method = findMethod("sendTouchEvent")
        assertNotNull("sendTouchEvent should exist", method)
        val paramTypes = method!!.parameterTypes
        assertEquals(5, paramTypes.size)
        assertEquals(Int::class.java, paramTypes[0])    // id
        assertEquals(Int::class.java, paramTypes[1])    // action
        assertEquals(Float::class.java, paramTypes[2])  // x
        assertEquals(Float::class.java, paramTypes[3])  // y
        assertEquals(Long::class.java, paramTypes[4])   // timestampMs
    }

    @Test
    fun `sendKeyEvent method exists with correct parameter types`() {
        val method = findMethod("sendKeyEvent")
        assertNotNull("sendKeyEvent should exist", method)
        val paramTypes = method!!.parameterTypes
        assertEquals(3, paramTypes.size)
        assertEquals(Int::class.java, paramTypes[0])    // androidKeyCode
        assertEquals(Int::class.java, paramTypes[1])    // action
        assertEquals(Long::class.java, paramTypes[2])   // timestampMs
    }

    @Test
    fun `sendPointerMotion method exists with correct parameter types`() {
        val method = findMethod("sendPointerMotion")
        assertNotNull("sendPointerMotion should exist", method)
        val paramTypes = method!!.parameterTypes
        assertEquals(3, paramTypes.size)
        assertEquals(Float::class.java, paramTypes[0])  // x
        assertEquals(Float::class.java, paramTypes[1])  // y
        assertEquals(Long::class.java, paramTypes[2])   // timestampMs
    }

    @Test
    fun `sendPointerButton method exists with correct parameter types`() {
        val method = findMethod("sendPointerButton")
        assertNotNull("sendPointerButton should exist", method)
        val paramTypes = method!!.parameterTypes
        assertEquals(3, paramTypes.size)
        assertEquals(Int::class.java, paramTypes[0])    // button
        assertEquals(Int::class.java, paramTypes[1])    // action
        assertEquals(Long::class.java, paramTypes[2])   // timestampMs
    }

    @Test
    fun `sendPointerScroll method exists with correct parameter types`() {
        val method = findMethod("sendPointerScroll")
        assertNotNull("sendPointerScroll should exist", method)
        val paramTypes = method!!.parameterTypes
        assertEquals(3, paramTypes.size)
        assertEquals(Float::class.java, paramTypes[0])  // dx
        assertEquals(Float::class.java, paramTypes[1])  // dy
        assertEquals(Long::class.java, paramTypes[2])   // timestampMs
    }

    @Test
    fun `commitText method exists with String parameter`() {
        val method = findMethod("commitText")
        assertNotNull("commitText should exist", method)
        val paramTypes = method!!.parameterTypes
        assertEquals(1, paramTypes.size)
        assertEquals(String::class.java, paramTypes[0])
    }

    @Test
    fun `notifyImeShown method exists with no parameters`() {
        val method = findMethod("notifyImeShown")
        assertNotNull("notifyImeShown should exist", method)
        assertEquals(0, method!!.parameterTypes.size)
    }

    @Test
    fun `notifyImeHidden method exists with no parameters`() {
        val method = findMethod("notifyImeHidden")
        assertNotNull("notifyImeHidden should exist", method)
        assertEquals(0, method!!.parameterTypes.size)
    }

    @Test
    fun `getImePipeFd method exists and returns Int`() {
        val method = findMethod("getImePipeFd")
        assertNotNull("getImePipeFd should exist", method)
        assertEquals(0, method!!.parameterTypes.size)
        assertEquals(Int::class.java, method.returnType)
    }

    @Test
    fun `CompositorInput has exactly 9 public methods`() {
        // touch, key, pointer motion, pointer button, pointer scroll,
        // commitText, imeShown, imeHidden, getImePipeFd
        val publicMethods = CompositorInput::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
        assertEquals(
            "Expected 9 public methods on CompositorInput",
            9,
            publicMethods.size,
        )
    }

    @Test
    fun `CompositorInput constructor takes CompositorServer`() {
        val constructors = CompositorInput::class.java.declaredConstructors
        assertEquals(1, constructors.size)
        val params = constructors[0].parameterTypes
        assertEquals(1, params.size)
        assertEquals(CompositorServer::class.java, params[0])
    }

    private fun findMethod(name: String): Method? =
        CompositorInput::class.java.declaredMethods.find { it.name == name }
}

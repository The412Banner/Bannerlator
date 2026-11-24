package com.winlator.star.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class IdleThrottleTest {
    @Test public void parseKeepsApiOrderAndDropsUnknownWords() {
        assertEquals(Arrays.asList("dx7", "dx11", "vk"), IdleThrottle.parse(" VK, dx11 ,dx7,dx7,foo "));
        assertEquals(Collections.emptyList(), IdleThrottle.parse("none"));
        assertEquals(Collections.emptyList(), IdleThrottle.parse(" NONE "));
        assertNull(IdleThrottle.parse(null));
        assertNull(IdleThrottle.parse(""));
        assertNull(IdleThrottle.parse("  "));
        assertNull(IdleThrottle.parse("foo,bar"));
    }

    @Test public void joinWritesApiOrderAndNoneForEmpty() {
        assertEquals("dx7,dx9,gl", IdleThrottle.join(Arrays.asList("gl", "dx9", "dx7")));
        assertEquals("none", IdleThrottle.join(Collections.emptyList()));
        assertEquals("none", IdleThrottle.join(null));
        assertEquals("none", IdleThrottle.join(Arrays.asList("dx13")));
        assertEquals("dx7,dx8,dx9,dx10,dx11,dx12,gl,vk", IdleThrottle.join(IdleThrottle.APIS));
    }

    @Test public void normalizeRoundTrips() {
        for (String v : new String[] {"dx7", "none", "dx7,dx8,dx9,dx10,dx11,dx12,gl,vk", "dx10,vk"}) {
            assertEquals(v, IdleThrottle.normalize(v));
            assertEquals(v, IdleThrottle.join(IdleThrottle.parse(v)));
        }
        assertNull(IdleThrottle.normalize("x"));
    }

    @Test public void gameValueWinsThenContainerThenDefault() {
        assertEquals("dx9", IdleThrottle.resolve("dx9", "dx7,gl"));
        assertEquals("none", IdleThrottle.resolve("none", "dx7"));
        assertEquals("dx7,gl", IdleThrottle.resolve("", "dx7,gl"));
        assertEquals("dx7,gl", IdleThrottle.resolve(null, "gl,dx7"));
        assertEquals("dx7,gl", IdleThrottle.resolve("garbage", "dx7,gl"));
        assertEquals("none", IdleThrottle.resolve(null, "none"));
        assertEquals("dx7", IdleThrottle.resolve(null, null));
        assertEquals("dx7", IdleThrottle.resolve("", ""));
    }

    @Test public void emptySetGoesToTheEnvAsNone() {
        assertEquals("none", IdleThrottle.fromOffList(""));
        assertEquals("none", IdleThrottle.resolve(IdleThrottle.fromOffList(""), "dx7"));
        assertEquals("dx7,dx12", IdleThrottle.fromOffList("dx12,dx7"));
    }

    @Test public void editorListForm() {
        assertEquals("", IdleThrottle.toOffList("none"));
        assertEquals("dx7", IdleThrottle.toOffList(null));
        assertEquals("dx7", IdleThrottle.toOffList("bad"));
        assertEquals("dx7,vk", IdleThrottle.toOffList("vk,dx7"));
    }

    @Test public void usersOwnVariableWins() {
        Set<String> env = new HashSet<>();
        assertNull(IdleThrottle.userVariable(env::contains));
        env.add("WINE_SLEEP0_THROTTLE");
        assertEquals("WINE_SLEEP0_THROTTLE", IdleThrottle.userVariable(env::contains));
        env.add("WINE_SLEEP0_THROTTLE_SKIP");
        assertEquals("WINE_SLEEP0_THROTTLE_SKIP", IdleThrottle.userVariable(env::contains));
    }

    @Test public void names() {
        assertEquals("DirectX 7", IdleThrottle.longName("dx7"));
        assertEquals("DirectX 12", IdleThrottle.longName("dx12"));
        assertEquals("OpenGL", IdleThrottle.longName("gl"));
        assertEquals("Vulkan", IdleThrottle.longName("vk"));
        assertEquals("DX7, OpenGL", IdleThrottle.offNames("gl,dx7"));
        assertEquals("", IdleThrottle.offNames("none"));
        assertEquals("DX7", IdleThrottle.offNames(null));
        assertTrue(IdleThrottle.APIS.size() == 8);
    }
}

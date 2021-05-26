package hudson.util;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;


public class MaskingClassLoaderTest {

    @Test(expected = ClassNotFoundException.class)
    public void loadClass() throws ClassNotFoundException {
        try {
            getClass().getClassLoader().loadClass(NotMe.class.getName());
        } catch (ClassNotFoundException e) {
            fail("Preconditions not met.");
        }
        MaskingClassLoader l = new MaskingClassLoader(getClass().getClassLoader(), NotMe.class.getName());
        l.loadClass(NotMe.class.getName());
    }

    @Test(expected = ClassNotFoundException.class)
    public void loadClassFromMaskedLibrary() throws ClassNotFoundException {
        try {
            getClass().getClassLoader().loadClass("com.google.common.collect.ArrayListMultimap");
        } catch (ClassNotFoundException e) {
            fail("Preconditions not met.");
        }
        MaskingClassLoader l = new MaskingClassLoader(getClass().getClassLoader()).loadLibraryMasks("libraries.list");
        l.loadClass("com.google.common.collect.ArrayListMultimap");
    }

    @Test
    public void getResource() {
        final String resName = MaskingClassLoader.class.getName().replace('.', '/') + "/libraries.list";
        assertNotNull("Preconditions not met.", MaskingClassLoader.class.getClassLoader().getResource(resName));
        MaskingClassLoader l = new MaskingClassLoader(MaskingClassLoader.class.getClassLoader(), MaskingClassLoader.class.getName());
        assertNull(l.getResource(resName));
    }

    @Test
    public void getResourceFromMaskedLibrary() {
        final String resName = "com/google/common/collect/ArrayListMultimap.class";
        assertNotNull("Preconditions not met.", getClass().getClassLoader().getResource(resName));
        MaskingClassLoader l = new MaskingClassLoader(getClass().getClassLoader()).loadLibraryMasks("libraries.list");
        assertNull(l.getResource(resName));
    }

    public static class NotMe {

    }
}

// copyright 2016 nqzero, 2014 sriram srinivasan - offered under the terms of the MIT License
package kilim.analysis;

import kilim.Constants;
import kilim.mirrors.CachedClassMirrors.ClassMirror;
import kilim.mirrors.CachedClassMirrors.MethodMirror;
import kilim.mirrors.ClassMirrorNotFoundException;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * {@code SAMweaver} generates code to support functional interfaces (also known
 * as SAM, for Single Abstract Method), where the SAM method is pausable. What
 * makes SAM interfaces special compared to regular interfaces is that they can
 * represent a lambda function in java8. More on this later.
 * 
 * Imagine that a class called <code>X</code> uses a <code>I</code> of the
 * following form:
 * 
 * <pre>
 * interface I {
 *     int foo(double d) throws Pausable;
 * }
 * </pre>
 * 
 * Since <code>I</code> is a SAM, {@code CallWeaver} replaces all
 * <code>invokeinterface I.foo(double)</code> with a static invocation of a tiny
 * wrapper method ('shim') in X like this:
 * 
 * <pre>
 *      invokestatic X.$shim$2(I callee, double d, Fiber f)
 * </pre>
 * 
 * The shim method in turn turns around and calls <code>I.foo(double)</code>:
 * 
 * <pre>
 *     private static int X.$shim$2(I callee, double d, Fiber f) {
 *        int ret = callee.f(d, f);
 *        f.setCallee(callee); // this is the purpose of the shim
 *        return ret;
 *     }
 * </pre>
 *
 * The purpose of {@code SAMweaver} is to generate the shim above.
 * 
 * <h3>Why?</h3>
 * <p>
 * Ordinarily, all hand-written code is modified by the weaver if it contains
 * definitions or invocations of pausable methods. Lambda expressions however
 * rely on the VM generating a class at run-time, which implements the
 * functional interface (<code>I</code> in the example). The problem is that
 * this class needs to be woven to support kilim Fibers, but we don't have an
 * easy portable hook to weave it at run-time.
 * <p>
 * As it turns out, practically all the weaving work is already complete at
 * compile time. This is because, the body of the lambda expression is already
 * available to the weaver as an ordinary method in the host class
 * <code>X</code>, and is treated like any another pausable method. In other
 * words, the transformations at the calling site and in the body of the called
 * method are as usual.
 * 
 * All that this is left for the shim to do is to capture the object's reference
 * (<code>f.setCallee</code>); the fiber needs it while resuming. The call to
 * setCallee is redundant for ordinary hand-written implementations of SAM
 * interfaces, but is necessary if the implementation happens to be generated by
 * the VM as described above.
 *
 * <p>
 * Of course, all this applies only if the functional method is pausable.
 */

public class SAMweaver implements Constants {
    String  interfaceName;
    String  methodName;
    String  desc;
    boolean itf;
    int     index = -1;
    KilimContext context;

    public SAMweaver(KilimContext context,String interfaceName, String methodName, String desc,
            boolean itf) {
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.desc = desc;
        this.itf = itf;
        this.context = context;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public boolean equals(Object obj) {
        if (obj instanceof SAMweaver) {
            SAMweaver that = (SAMweaver) obj;
            return desc.equals(that.desc) && methodName.equals(that.methodName)
                    && interfaceName.equals(that.interfaceName);
        }
        return false;
    }
    
    public String toString() {
        return interfaceName+"."+methodName+desc + " ->" +getShimMethodName();
    }

    public int hashCode() {
        return methodName.hashCode() ^ desc.hashCode();
    }

    String getShimMethodName() {
        assert index >= 0;
        return SAM_SHIM_PREFIX + index;
    }

    String getShimDesc() {
        return this.desc.replace("(", "(" + TypeDesc.getInterned(this.interfaceName))
                .replace(")", Constants.D_FIBER_LAST_ARG);
    }

    /**
     * Generate a method like this:
     * 
     * <pre>
     * private static $shim$1 (I callee, ...args..., f fiber) {
     *    load each arg
     *    call interface.method
     *    f.setCallee(arg0)
     *    xret
     * }
     * </pre>
     * 
     * @param cv
     */
    public void accept(ClassVisitor cv) {
        String shimDesc = getShimDesc();
        MethodVisitor mv = cv.visitMethod(ACC_STATIC | ACC_PRIVATE, getShimMethodName(), shimDesc, null, 
                getExceptions());
        // load arguments
        int ivar = 0;
        for (String argType : TypeDesc.getArgumentTypes(shimDesc)) {
            int vmt = VMType.toVmType(argType);
            mv.visitVarInsn(VMType.loadInsn[vmt], ivar);
            ivar += VMType.category[vmt]; // register width = 2 if double or
                                          // long, 1 otherwise
        }
        int fiberVar = ivar - 1;

        // invoke interface
        String fiberDesc = desc.replace(")", Constants.D_FIBER + ")");
        /*
         * Fixed java.lang.IncompatibleClassChangeError
         * Show in testcase kilim.test.TestAbstractExtends
         */
        if(itf) {
        	mv.visitMethodInsn(INVOKEINTERFACE, interfaceName, methodName, fiberDesc, true);
        } else {
        	mv.visitMethodInsn(INVOKEVIRTUAL, interfaceName, methodName, fiberDesc, false);
        }

        // store callee object reference in fiber 
        mv.visitVarInsn(ALOAD, fiberVar);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, FIBER_CLASS, "setCallee", "(" + D_OBJECT + ")V", false);

        // return .. RETURN (if void) or ARETURN, IRETURN, etc.
        String retDesc = TypeDesc.getReturnTypeDesc(shimDesc);
        if (retDesc.charAt(0) == 'V') {
            mv.visitInsn(RETURN);
        } else {
            int vmt = VMType.toVmType(retDesc);
            mv.visitInsn(VMType.retInsn[vmt]);
        }
        mv.visitMaxs(0, 0); // maxLocals and maxStackDepth will be computed by asm's MethodWriter
        mv.visitEnd();
    }

    static String [] internalName(String [] words) {
        if (words==null) return words;
        String [] mod = new String[words.length];
        for (int ii = 0; ii < mod.length; ii++) mod[ii] = words[ii].replace('.','/');
        return mod;
    }

    private String[] getExceptions() {
        try {
            ClassMirror cm = context.detector.classForName(interfaceName);
            for (MethodMirror m : cm.getDeclaredMethods()) {
                if (m.getName().equals(this.methodName)
                        && m.getMethodDescriptor().equals(this.desc)) {
                    // Convert dots to slashes. 
                    String[] ret = m.getExceptionTypes();
                    if (ret != null) 
                        return internalName(ret);
                    break;
                }
            }
        } catch (ClassMirrorNotFoundException cmnfe) {
        }

        // Should not happen at this stage. If this class weren't found, it
        // would have created a
        // problem much earlier on.
        assert false : "Class Mirror not found for interface " + interfaceName;
        return new String[0];
    }
}

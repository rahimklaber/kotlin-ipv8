// Automatically generated by flapigen
package generated;


public final class SchnorrSignWrapper {

    public SchnorrSignWrapper() {
        mNativeObj = init();
    }
    private static native long init();

    public static SchnorrSignWrapper new_instance_for_signing(SchnorrKeyWrapper key, long threshold) {
        long a0 = key.mNativeObj;
        long ret = do_new_instance_for_signing(a0, threshold);
        SchnorrSignWrapper convRet = new SchnorrSignWrapper(InternalPointerMarker.RAW_PTR, ret);
        java.lang.ref.Reference.reachabilityFence(key);

        return convRet;
    }
    private static native long do_new_instance_for_signing(long key, long threshold);

    public static SignResult1 sign_1_preprocess(SchnorrSignWrapper wrapper) {
        long a0 = wrapper.mNativeObj;
        wrapper.mNativeObj = 0;

        long ret = do_sign_1_preprocess(a0);
        SignResult1 convRet = new SignResult1(InternalPointerMarker.RAW_PTR, ret);
        java.lang.ref.Reference.reachabilityFence(wrapper);

        return convRet;
    }
    private static native long do_sign_1_preprocess(long wrapper);

    public static SignResult2 sign_2_sign_bitcoin(SchnorrSignWrapper wrapper, SignParams2 params, byte [] msg_i8, byte [] prev_out_script) {
        long a0 = wrapper.mNativeObj;
        wrapper.mNativeObj = 0;

        long a1 = params.mNativeObj;
        params.mNativeObj = 0;

        long ret = do_sign_2_sign_bitcoin(a0, a1, msg_i8, prev_out_script);
        SignResult2 convRet = new SignResult2(InternalPointerMarker.RAW_PTR, ret);
        java.lang.ref.Reference.reachabilityFence(wrapper);
        java.lang.ref.Reference.reachabilityFence(params);

        return convRet;
    }
    private static native long do_sign_2_sign_bitcoin(long wrapper, long params, byte [] msg_i8, byte [] prev_out_script);

    public static SignResult2 sign_2_sign_normal(SchnorrSignWrapper wrapper, SignParams2 params, byte [] msg_i8) {
        long a0 = wrapper.mNativeObj;
        wrapper.mNativeObj = 0;

        long a1 = params.mNativeObj;
        params.mNativeObj = 0;

        long ret = do_sign_2_sign_normal(a0, a1, msg_i8);
        SignResult2 convRet = new SignResult2(InternalPointerMarker.RAW_PTR, ret);
        java.lang.ref.Reference.reachabilityFence(wrapper);
        java.lang.ref.Reference.reachabilityFence(params);

        return convRet;
    }
    private static native long do_sign_2_sign_normal(long wrapper, long params, byte [] msg_i8);

    public static byte [] sign_3_complete(SchnorrSignWrapper wrapper, SignParams3 params) {
        long a0 = wrapper.mNativeObj;
        wrapper.mNativeObj = 0;

        long a1 = params.mNativeObj;
        params.mNativeObj = 0;

        byte [] ret = do_sign_3_complete(a0, a1);
        java.lang.ref.Reference.reachabilityFence(wrapper);
        java.lang.ref.Reference.reachabilityFence(params);

        return ret;
    }
    private static native byte [] do_sign_3_complete(long wrapper, long params);

    public synchronized void delete() {
        if (mNativeObj != 0) {
            do_delete(mNativeObj);
            mNativeObj = 0;
       }
    }
    @Override
    protected void finalize() throws Throwable {
        try {
            delete();
        }
        finally {
             super.finalize();
        }
    }
    private static native void do_delete(long me);
    /*package*/ SchnorrSignWrapper(InternalPointerMarker marker, long ptr) {
        assert marker == InternalPointerMarker.RAW_PTR;
        this.mNativeObj = ptr;
    }
    /*package*/ long mNativeObj;
}
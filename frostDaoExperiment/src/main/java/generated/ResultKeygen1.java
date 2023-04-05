// Automatically generated by flapigen
package generated;


public final class ResultKeygen1 {

    public ResultKeygen1(SchnorrKeyGenWrapper keygen, byte [] res) {
        long a0 = keygen.mNativeObj;
        keygen.mNativeObj = 0;

        mNativeObj = init(a0, res);        java.lang.ref.Reference.reachabilityFence(keygen);
    }
    private static native long init(long keygen, byte [] res);

    public static SchnorrKeyGenWrapper get_keygen(ResultKeygen1 result) {
        long a0 = result.mNativeObj;
        result.mNativeObj = 0;

        long ret = do_get_keygen(a0);
        SchnorrKeyGenWrapper convRet = new SchnorrKeyGenWrapper(InternalPointerMarker.RAW_PTR, ret);
        java.lang.ref.Reference.reachabilityFence(result);

        return convRet;
    }
    private static native long do_get_keygen(long result);

    public final byte [] get_res() {
        byte [] ret = do_get_res(mNativeObj);

        return ret;
    }
    private static native byte [] do_get_res(long self);

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
    /*package*/ ResultKeygen1(InternalPointerMarker marker, long ptr) {
        assert marker == InternalPointerMarker.RAW_PTR;
        this.mNativeObj = ptr;
    }
    /*package*/ long mNativeObj;
}
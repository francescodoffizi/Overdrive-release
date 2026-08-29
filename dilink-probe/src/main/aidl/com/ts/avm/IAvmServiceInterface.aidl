package com.ts.avm;

import com.ts.avm.IAvmServiceListener;
import com.ts.avm.IAvmCallListener;
import com.ts.avm.IAvmServiceCommandListener;
import com.ts.avm.bean.CmdBean;

interface IAvmServiceInterface {
    int getAvmStatus();
    int registerAvmStatusListener(IAvmServiceListener listener);
    int registerCallStatusListener(IAvmCallListener listener);
    int registerCommandListener(IAvmServiceCommandListener listener);
    int sendCallInfo(String str, String str2, String str3, int i, int i2, int i3, boolean z);
    void sendCommand(in CmdBean cmdBean);
    int sendMultiCallInfo(in List<String> list);
    void startAvm();
    void stopAvm();
    int unregisterAvmStatusListener(IAvmServiceListener listener);
    int unregisterCallStatusListener(IAvmCallListener listener);
}

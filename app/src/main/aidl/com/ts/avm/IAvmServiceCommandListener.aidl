package com.ts.avm;

import com.ts.avm.bean.CmdBean;

oneway interface IAvmServiceCommandListener {
    void onCommandReceived(in CmdBean cmdBean);
}

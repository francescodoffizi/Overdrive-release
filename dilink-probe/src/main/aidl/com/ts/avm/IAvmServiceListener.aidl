package com.ts.avm;

oneway interface IAvmServiceListener {
    void onAvmServiceStatusChanged(int status, String extra);
}

package com.mediatek.rns;

import android.os.Messenger;

/** {@hide} */
interface IRnsManager
{
    int getAllowedRadioList(int capability);
    void registerRnsAgent(String name, in Messenger messenger);
    int getTryAnotherRadioType(int failedNetType);
    int getRnsState();
}
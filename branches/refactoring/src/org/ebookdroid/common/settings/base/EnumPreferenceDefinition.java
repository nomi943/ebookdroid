package org.ebookdroid.common.settings.base;

import org.ebookdroid.EBookDroidApp;

import android.content.SharedPreferences;

import org.emdev.utils.enums.EnumUtils;
import org.emdev.utils.enums.ResourceConstant;


public class EnumPreferenceDefinition<E extends Enum<E> & ResourceConstant> extends BasePreferenceDefinition {
    
    private Class<E> enumClass;
    private E defValue;
    
    public EnumPreferenceDefinition(Class<E> enumClass, final int keyRes, final int defValRef) {
        super(keyRes);
        this.enumClass  =enumClass;
        this.defValue = EnumUtils.getByResValue(enumClass, EBookDroidApp.context.getString(defValRef), null);
    }
 
    public E getPreferenceValue(final SharedPreferences prefs) {
        return EnumUtils.getByResValue(enumClass, prefs.getString(key, null), defValue);
    }
}

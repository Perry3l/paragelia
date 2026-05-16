package com.ads.paragelia;

import java.util.Map;

public class CurrentTableHolder {
    private static Map<String, Object> tableData;
    private static String tableNumber;

    public static void set(String number, Map<String, Object> data) {
        tableNumber = number;
        tableData = data;
    }

    public static String getTableNumber() { return tableNumber; }
    public static Map<String, Object> getTableData() { return tableData; }

    public static void clear() {
        tableData = null;
        tableNumber = null;
    }
}
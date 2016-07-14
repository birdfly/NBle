package com.tggg.nble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import timber.log.Timber;
import com.tggg.nble.device.DeviceBase;
import com.tggg.nble.ifunction.IBleNotifyFunction;

/**
 * Created by Gang Tong.
 */
public class NBleDevice extends DeviceBase {

    public static UUID SERVICES_DEVICE_INFO_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static UUID CHARACTERISTICS_SOFTWARE_UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    private BluetoothGattCharacteristic sofetwareversion = null;
    /**
     * 序列化字段
     */
    public static final String SERIALIZE_ADDRESS = "address";
    public static final String SERIALIZE_NAME = "name";

    /**
     * Enable Notification的UUID
     */
    public static final UUID DESCRIPTOR_ENABLE_NOTIFICATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Context context;
    private BluetoothGatt bleGatt;
    private BluetoothAdapter bluetoothAdapter;

    /**
     * 是否维护。如果true，表示device在disconnected后，会重新去做connect。
     */
    private boolean bMaintain = false;

    /**
     * 记录当前device是否在连接中。
     */
    private boolean isConnecting = false;

    /**
     * 通知接口。当有Notification达到时，调用此接口。
     */
    private IBleNotifyFunction iBleNotifyFunction;

    /**
     * 调试用。主要是记录device的各个连接事件。便于回溯历史。
     */
    private List<StatusChangeRecord> statusRecordList = Collections.synchronizedList(new ArrayList<StatusChangeRecord>());


    private NBleDevice() {
        super(null, null);
        // prevent instantiation
    }

    @Override
    public String getAddress() {
        return super.getAddress();
    }

    /**
     * 获取设备名称
     */
    public String getName() {
        return super.getName();
    }

    /**
     * 判断是否是维护状态
     */
    public boolean isMaintain() {
        return bMaintain;
    }

    /**
     * 设置维护状态
     *
     * @param maintain
     */
    public void setMaintain(boolean maintain) {
        this.bMaintain = maintain;
        NBleDeviceManager.getInstance().storeDevices();
    }

    /**
     * 获取状态记录
     */
    public List<StatusChangeRecord> getStatusRecordList() {
        return new ArrayList<>(statusRecordList);
    }

    /**
     * 获取device
     */
    public BluetoothDevice getBleDevice() {
        return this.bleGatt.getDevice();
    }

    public synchronized boolean write(UUID serviceUuid, UUID characteristicUuid, byte[] data) {

        if (bleGatt == null) {
            Timber.e("gatt not connected: %s", getAddress());
            return false;
        }

        BluetoothGattService service = bleGatt.getService(serviceUuid);
        if (service == null) {
            Timber.e("service null: %s", serviceUuid.toString());
            return false;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);
        if (characteristic == null) {
            Timber.e("characteristic null: %s", characteristicUuid.toString());
            return false;
        }

        characteristic.setValue(data);
        boolean retValue = bleGatt.writeCharacteristic(characteristic);
        Timber.i("writeCharacteristic result: %b", retValue);
        return retValue;
    }

    public synchronized boolean read(UUID serviceUuid, UUID characteristicUuid) {
        boolean retValue;
        if (bleGatt == null) {
            Timber.e("gatt not connected: %s", getAddress());
            return false;
        }
        BluetoothGattService service = null;
        try {
            service = bleGatt.getService(serviceUuid);
        } catch (Exception e) {
            Timber.e(e.getMessage());
        }
        if (service == null) {
//            Timber.e("service null: %s", serviceUuid.toString());
//            Timber.e("service count: %d", bleGatt.getServices().size());
//            for (BluetoothGattService ser : bleGatt.getServices()) {
//                Timber.e("service uuid: %s", ser.getUuid().toString());
//            }
            return false;
        }

        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);
        if (characteristic == null) {
            Timber.e("characteristic null: %s", characteristicUuid.toString());
            return false;
        }

        retValue = bleGatt.readCharacteristic(characteristic);
        Timber.i("readCharacteristic result: %b", retValue);
        return retValue;
    }

    public IBleNotifyFunction getiNotifyFunction() {
        return this.iBleNotifyFunction;
    }

    /**
     * 请求Rssi值。
     */
    public synchronized void requestRemoteRssi() {
        if (bleGatt == null) {
            Timber.e("gatt not connected: %s", getAddress());
            return;
        }

        bleGatt.readRemoteRssi();
    }

    /**
     * 从Manager中去除
     */
    private void removeFromManager() {
        NBleDeviceManager.getInstance().remove(getAddress());
    }


    /**
     * 添加到Manager中
     */
    private NBleDevice addToManager() {
        NBleDeviceManager.getInstance().add(this);
        return this;
    }

    /**
     * 获取当前设备的连接状态
     */
    public synchronized int getConnectionState() {

        if (bleGatt == null || bleGatt.getDevice() == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        BluetoothDevice device = bleGatt.getDevice();
        int state = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getConnectionState(device, BluetoothProfile.GATT);
        Timber.d("getConnectedState() addr:%s, state:%s", device.getAddress(), BluetoothUtil.connectionStateToString(state));

        if (isConnecting) {
            state = BluetoothProfile.STATE_CONNECTING;
            Timber.d("getConnectedState() addr:%s, return state:%s", device.getAddress(), BluetoothUtil.connectionStateToString(state));
            return state;
        }

        state = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getConnectionState(device, BluetoothProfile.GATT);
        Timber.d("getConnectedState() addr:%s, return state:%s", device.getAddress(), BluetoothUtil.connectionStateToString(state));

        return state;
    }

    /**
     * 断开连接。
     */
    @Override
    public synchronized void disconnect() {
        if (bleGatt != null) {
            Timber.i("disconnect() isConnecting:%s, address: %s", Boolean.toString(isConnecting), getAddress());
            statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.DISCONNECT));
            if (isConnecting) {
                isConnecting = false;
                bleGatt.disconnect();
                // 在连接过程中做disconnect，会导致连接中断，且没有回调。
                // 所以需要做close，以及后续的判断处理。
                observable.subscribeOn(Schedulers.immediate())
                        .filter(new Func1<String, Boolean>() {
                            @Override
                            public Boolean call(String s) {
                                return bluetoothAdapter.isEnabled() && NBleDeviceManager.getInstance().isMaintain(getAddress());
                            }
                        })
                        .delay(1, TimeUnit.SECONDS)
                        .observeOn(Schedulers.newThread())
                        .subscribe(connectAction);

            } else {
                // 如果当前在连接状态，则会触发断开的回调函数。在回调中处理是否close，以及是否需要重连。
                isConnecting = false;
                bleGatt.disconnect();
            }
        } else {
            isConnecting = false;
        }
    }

    /**
     * Connect to the device directly
     */
    @Override
    public boolean connect() {
        // 当直接连接时候，一般都由于经过scan后找到的。所以，autoConnection设为false
        return connect(false);
    }

    /**
     * 连接Device
     *
     * @param autoConnect， 表示是否是直连，还是auto连接。
     * @return
     */
    private synchronized boolean connect(boolean autoConnect) {

        if (bluetoothAdapter == null) {
            bluetoothAdapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        }

        if (!bluetoothAdapter.isEnabled()) {
            return false;
        }

        if (!BluetoothAdapter.checkBluetoothAddress(getAddress())) {
            Timber.e("invalid address");
            return false;
        }

        int state = getConnectionState();
        if (state != BluetoothProfile.STATE_DISCONNECTED || isConnecting) {
            Timber.w("Current state is %s, so cannot do connect operation. isConnecting: %b", state, isConnecting);
            return false;
        }

        // 如果bleGate只是disconnect了，没有close。并再尝试通过connectGatt连接时，会导致2次回调。
        if (bleGatt != null) bleGatt.close();

        BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(getAddress());
        Timber.i("Start connectGatt() address: %s, autoConnect:%b", getAddress(), autoConnect);
        isConnecting = true;

        statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.CONNECT));
        if (iBleNotifyFunction != null) {
            iBleNotifyFunction.onConnecting(context, getAddress());
        }

        bleGatt = bluetoothDevice.connectGatt(context, autoConnect, gattCallBack);

        if (bleGatt == null) {
            isConnecting = false;
            statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.CONNECTED_ERROR));
            if (iBleNotifyFunction != null) {
                iBleNotifyFunction.onConnecting(context, getAddress());
            }
            return false;
        }

        Timber.d("connecting address");
        return true;
    }

    @Override
    public String serialize() {
        JSONObject object = new JSONObject();
        object.put(SERIALIZE_ADDRESS, getAddress());
        object.put(SERIALIZE_NAME, getName());
        return object.toJSONString();
    }

    static public NBleDevice deserialize(Context context, String json) {
        JSONObject object = JSON.parseObject(json);
        String address = object.getString(SERIALIZE_ADDRESS);
        String name = object.getString(SERIALIZE_NAME);
        return new Builder(address, name).build(context);
    }

    Observable observable = Observable.create(new Observable.OnSubscribe<String>() {
        @Override
        public void call(Subscriber<? super String> subscriber) {
            closeAction.call(getAddress());
            subscriber.onNext(getAddress());
        }
    });

    Action1<String> closeAction = new Action1<String>() {
        @Override
        public void call(String address) {
            synchronized (NBleDevice.this) {
                Timber.i("Start Close Action to close device " + address);
                // close device
                isConnecting = false;
                if (bleGatt != null) {
                    bleGatt.close();
                    bleGatt = null;
                    statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.CLOSE));
                    if (iBleNotifyFunction != null) {
                        iBleNotifyFunction.onClose(context, address);
                    }
                }
            }
        }
    };

    Action1<String> connectAction = new Action1<String>() {
        @Override
        public void call(String address) {
            connect(true);
        }
    };

    private final BluetoothGattCallback gattCallBack = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            final String address = gatt.getDevice().getAddress();
            String deviceName = gatt.getDevice().getName();

            Timber.i(getName() + ", " + address + ", " + iBleNotifyFunction.getClass().getName() + ", Connection operation status: " + BluetoothUtil.statusToString(status) + ", New connection state: " + BluetoothUtil.connectionStateToString(newState));

            try {
                isConnecting = false;
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            // 更新device name
                            if (deviceName != null) {
                                setName(deviceName);
                            }

                            statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.CONNECTED));

                            gatt.discoverServices();

                            if (iBleNotifyFunction != null) {
                                iBleNotifyFunction.onConnected(context, gatt.getDevice().getAddress());
                            }
                        } else {
                            // status == GATT_ERROR
                            // 异常断开，需要close后重连
                            statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.CONNECTED_ERROR));
                            throw new ConnectException();
                        }
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:

                        // 由于某些非主动删除的原因，导致的disconnect，需要重新连接。例如：距离变远、断电、等。
                        // 如果用户主动disconnect，需要手动removeFromMaintain，否则也会重新连接。
                        statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.DISCONNECTED));

                        if (iBleNotifyFunction != null) {
                            iBleNotifyFunction.onDisConnected(context, gatt.getDevice().getAddress());
                        }

                        if (bluetoothAdapter.isEnabled() && NBleDeviceManager.getInstance().isMaintain(address)) {
                            Timber.d("Device " + address + " is in maintain list");
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                Timber.i(address + " gatt.connect()");
                                if (gatt.connect()) {
                                    Timber.d("When get STATE_DISCONNECTED, gatt.connect() return TRUE! address:%s", address);
                                    isConnecting = true;
                                    statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.AUTOCONNECT));
                                    if (iBleNotifyFunction != null) {
                                        iBleNotifyFunction.onConnecting(context, gatt.getDevice().getAddress());
                                    }
                                } else {
                                    Timber.w("When get STATE_DISCONNECTED, gatt.connect() return FALSE! address:%s", address);
                                    statusRecordList.add(new StatusChangeRecord(StatusChangeRecord.AUTOCONNECT_FAIL));
                                    throw new ConnectException();
                                }
                            } else {
                                // status == GATT_FAILURE, 属于connectGatt时,registerClient失败，需要close后重连
                                // status == 133, 属于异常断开，需要close后重连
                                throw new ConnectException();
                            }
                        } else {
                            Timber.d("bluetooth adapter is DISABLE or NOT in maintain list.");
                            throw new ConnectException();
                        }
                        break;
                    default:
                        // NO OP
                }
            } catch (ConnectException e) {

                observable.subscribeOn(Schedulers.newThread())
                        .filter(new Func1<String, Boolean>() {
                            @Override
                            public Boolean call(String s) {
                                return bluetoothAdapter.isEnabled() && NBleDeviceManager.getInstance().isMaintain(address);
                            }
                        })
                        .delay(1, TimeUnit.SECONDS)
                        .observeOn(Schedulers.newThread())
                        .subscribe(connectAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Timber.d("Discovered: addr:%s, name:%s", gatt.getDevice().getAddress(), gatt.getDevice().getName());
            List<BluetoothGattService> services = gatt.getServices();

            for (BluetoothGattService service : services) {
                for (BluetoothGattCharacteristic chara : service.getCharacteristics()) {

                    if ((chara.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        gatt.setCharacteristicNotification(chara, true);
                        BluetoothGattDescriptor descriptor = chara.getDescriptor(DESCRIPTOR_ENABLE_NOTIFICATION);
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        boolean writeSuccess = gatt.writeDescriptor(descriptor);
                        Timber.i("writeSuccess %s", writeSuccess);
                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (gatt == null || characteristic == null || gatt.getDevice() == null) {
                return;
            }
            Timber.i("read: " + gatt.getDevice().getAddress() + "))" + characteristic.getStringValue(0) + " Status: " + status);
            if (iBleNotifyFunction != null) {
                iBleNotifyFunction.onRead(context, gatt.getDevice().getAddress(), characteristic.getUuid(), status == BluetoothGatt.GATT_SUCCESS ? characteristic.getValue() : null);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Timber.d("Write confirm: " + gatt.getDevice().getAddress() + "))" + characteristic.getStringValue(0) + " status: " + status);
            if (iBleNotifyFunction != null) {
                iBleNotifyFunction.onWrite(context, gatt.getDevice().getAddress(), characteristic.getUuid(), status == BluetoothGatt.GATT_SUCCESS ? characteristic.getValue() : null);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();

            //
            // Timber.i("Notification Addr:%s\tChara:%s\tValue:%s\t", gatt.getDevice().getAddress(), characteristic.getUuid().toString(), StringUtil.getHexString(value));
            if (iBleNotifyFunction != null) {
                UUID[] notifyUuids = iBleNotifyFunction.getNotifyUuid();
                if (notifyUuids != null) {
                    for (UUID uuid : notifyUuids) {
                        if (uuid.equals(characteristic.getUuid())) {
                            iBleNotifyFunction.onNotify(context, gatt.getDevice().getAddress(), characteristic.getUuid(), characteristic.getValue());
                            return;
                        }
                    }
                }
            }
        }


        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Timber.d("onDescriptorRead: " + gatt.getDevice().getAddress() + "))" + descriptor.toString() + " status: " + status);

        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Timber.d("onDescriptorWrite: " + gatt.getDevice().getAddress() + "))" + descriptor.toString() + " status: " + status);
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            Timber.d("onReliableWriteCompleted: " + gatt.getDevice().getAddress() + " status: " + status);

        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
//            Timber.d("onReadRemoteRssi: " + gatt.getDevice().getAddress() + " rssi: " + rssi + " status: " + status);
            if (iBleNotifyFunction != null) {
                iBleNotifyFunction.onRssi(context, gatt.getDevice().getAddress(), rssi);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Timber.d(gatt.getDevice().getAddress() + " mtu: " + mtu + " status: " + status);
        }
    };


    /**
     * BLE Device Builder
     */
    public static class Builder {
        private Context context;
        private String address;
        private String name;
        private boolean isMaintain;
        private IBleNotifyFunction iBleNotifyFunction;

        private Builder() {
        }

        public Builder(String address, String name) {
            this.address = address;
            this.name = name;
        }

        public Builder isMaintain(boolean isMaintain) {
            this.isMaintain = isMaintain;
            return this;
        }

        public Builder setINotifyFunction(IBleNotifyFunction iBleNotifyFunction) {
            this.iBleNotifyFunction = iBleNotifyFunction;
            return this;
        }

        public NBleDevice build(Context context) {
            this.context = context;
            return new NBleDevice(this).addToManager();
        }
    }

    private NBleDevice(Builder b) {
        super(b.address, b.name);
        context = b.context;
        bMaintain = b.isMaintain;
        if (b.iBleNotifyFunction != null) {
            iBleNotifyFunction = b.iBleNotifyFunction;
        } else {
            // 根据设备名获取notifyfunction
            iBleNotifyFunction = NBleDeviceManager.getInstance().getNotification(b.name);
            Timber.d("Builder set iBleNotifyFunction: %s", iBleNotifyFunction == null ? "null" : iBleNotifyFunction.getClass().getName());
        }
    }


    /**
     * 连接状态的记录。用于调试时候使用。
     */
    public class StatusChangeRecord {
        public static final int DISCONNECTED = 0;
        public static final int CONNECTED = 1;
        public static final int DISCONNECT = 2;
        public static final int CLOSE = 3;
        public static final int CONNECT = 4;
        public static final int RECONNECT = 5;
        public static final int AUTOCONNECT = 6;
        public static final int AUTOCONNECT_FAIL = 7;
        public static final int CONNECTED_ERROR = 8;
        private int type;
        private long timestamp;

        public StatusChangeRecord(int type) {
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }

        public int getType() {
            return type;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    static public String record2String(int type) {
        switch (type) {
            case StatusChangeRecord.DISCONNECTED:
                return "DISCONNECTED";

            case StatusChangeRecord.CONNECTED:
                return "CONNECTED";

            case StatusChangeRecord.DISCONNECT:
                return "DISCONNECT";

            case StatusChangeRecord.CLOSE:
                return "CLOSE";

            case StatusChangeRecord.CONNECT:
                return "CONNECT";

            case StatusChangeRecord.RECONNECT:
                return "RECONNECT";

            case StatusChangeRecord.AUTOCONNECT:
                return "AUTOCONNECT";

            case StatusChangeRecord.AUTOCONNECT_FAIL:
                return "AUTOCONNECT_FAIL";

            case StatusChangeRecord.CONNECTED_ERROR:
                return "CONNECTED_ERROR";

        }
        return "Unknow";
    }

    private class ConnectException extends Exception {
    }
}

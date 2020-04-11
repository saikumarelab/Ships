package net.videgro.ships.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.videgro.ships.Analytics;
import net.videgro.ships.MyFirebaseMessagingRepeater;
import net.videgro.ships.Repeater;
import net.videgro.ships.SettingsUtils;
import net.videgro.ships.Utils;
import net.videgro.ships.listeners.ShipReceivedListener;
import net.videgro.ships.nmea2ship.Nmea2Ship;
import net.videgro.ships.nmea2ship.domain.Ship;
import net.videgro.ships.services.internal.NmeaMessagesCache;
import net.videgro.ships.tasks.NmeaUdpClientTask;
import net.videgro.ships.tasks.NmeaUdpClientTask.NmeaUdpClientListener;
import net.videgro.ships.tasks.domain.DatagramSocketConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class NmeaClientService extends Service implements NmeaUdpClientListener {
	private static final String TAG = "NmeaClientService";

    /**
     * INTERNAL: Received from RTL-SDR dongle
     * EXTERNAL: Received from external party (show as 'peers')
     * Both via UDP
     * CLOUD:    Received from Firebase
     */
    public enum Source { INTERNAL, EXTERNAL, CLOUD }

	public static final int NMEA_UDP_PORT=10109;
    public static final String NMEA_UDP_HOST="127.0.0.1";

	private final IBinder binder = new ServiceBinder();
	private final Set<ShipReceivedListener> listeners=new HashSet<>();

    private Nmea2Ship nmea2Ship = new Nmea2Ship();

    private static final Source[] SOURCES={Source.INTERNAL,Source.EXTERNAL,Source.CLOUD};

    private Map<Source,DatagramSocketConfig> clients=new HashMap<>();
    private Map<Source,Boolean> mustRepeatFrom=new HashMap<>();

    private Repeater repeater=null;
    private NmeaMessagesCache cache;

	private Map<Source,NmeaUdpClientTask> nmeaUdpClientTasks=new HashMap<>();

    /**
     * Contains all received MMSIs. A set contains unique entries.
     */
    private Map<Source,Set<Integer>> mmsiReceived = new HashMap<>();

    private ConnectivityChangeReceiver connectivityChangeReceiver=new ConnectivityChangeReceiver();

    @Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

    @Override
    public boolean onUnbind(Intent intent) {
        for (final Source src:SOURCES) {
            final Set<Integer> mmsiReceivedSrc = mmsiReceived.get(src);
            if (mmsiReceivedSrc != null) {
                if (mmsiReceivedSrc.isEmpty()) {
                    Analytics.logEvent(this, Analytics.CATEGORY_STATISTICS, "No ships received - " + src.name(), Utils.retrieveAbi());
                } else {
                    Analytics.logEvent(this, Analytics.CATEGORY_STATISTICS, "Number of received ships - " + src.name(), Utils.retrieveAbi(), mmsiReceivedSrc.size());
                }
            }
        }
        return super.onUnbind(intent);
    }

    @Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate");

        mmsiReceived.put(Source.INTERNAL,new HashSet<>());
        mmsiReceived.put(Source.EXTERNAL,new HashSet<>());
        mmsiReceived.put(Source.CLOUD,new HashSet<>());

        nmeaUdpClientTasks.put(Source.INTERNAL,null);
        nmeaUdpClientTasks.put(Source.EXTERNAL,null);

        mustRepeatFrom.put(Source.INTERNAL,Boolean.FALSE);
        mustRepeatFrom.put(Source.EXTERNAL,Boolean.FALSE);

        // Never repeat messages which has CLOUD as source, to prevent loop
        mustRepeatFrom.put(Source.CLOUD,Boolean.FALSE);

		SettingsUtils.getInstance().init(this);

        registerReceiver(connectivityChangeReceiver,new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        LocalBroadcastManager.getInstance(this).registerReceiver((messageReceiver),new IntentFilter(MyFirebaseMessagingService.LOCAL_BROADCAST_TOPIC));
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		final String tag="onStartCommand - ";
		int result = super.onStartCommand(intent, flags, startId);
		Log.d(TAG,tag);
        init();
        return result;
	}

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        deinit();
        unregisterReceiver(connectivityChangeReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
        Analytics.logEvent(this,TAG, "destroy", "");
    }

    public void init(){
        deinit();

        mustRepeatFrom.put(Source.INTERNAL,SettingsUtils.getInstance().parseFromPreferencesRepeatInternal());
        mustRepeatFrom.put(Source.EXTERNAL,SettingsUtils.getInstance().parseFromPreferencesRepeatExternal());

        final boolean mustRepeatToCloud=SettingsUtils.getInstance().parseFromPreferencesRepeatToCloud();

        // Log 'must repeat'-settings
        Analytics.logEvent(this,Analytics.CATEGORY_NMEA_REPEAT, "RepeatNMEA_UserPreferences_INTERNAL",String.valueOf(mustRepeatFrom.get(Source.INTERNAL)));
        Analytics.logEvent(this,Analytics.CATEGORY_NMEA_REPEAT, "RepeatNMEA_UserPreferences_EXTERNAL",String.valueOf(mustRepeatFrom.get(Source.EXTERNAL)));
        Analytics.logEvent(this,Analytics.CATEGORY_NMEA_REPEAT, "RepeatNMEA_UserPreferences_TO_CLOUD",String.valueOf(mustRepeatToCloud));

        createClientConfigs();
        repeater=new Repeater(this,createRepeaterConfigs(),mustRepeatToCloud);

        repeater.startFirebaseMessaging();

        cache=new NmeaMessagesCache(getCacheDir(),repeater);

        if (Utils.haveNetworkConnection(this)){
            cache.processCachedMessages();
        }

        createAndStartNmeaUdpClientTasks();
    }

    private void deinit(){
        if (repeater!=null) {
            repeater.stopFirebaseMessaging();
        }
        repeater=null;

        for (final Source source:SOURCES) {
            final NmeaUdpClientTask task = nmeaUdpClientTasks.get(source);
            if (task != null && !task.isCancelled()) {
                task.cancel(true);
                nmeaUdpClientTasks.put(source,null);
            }
        }
    }

    private void createClientConfigs() {
        clients.clear();

        // Internal source
        clients.put(Source.INTERNAL,new DatagramSocketConfig(NMEA_UDP_HOST,NMEA_UDP_PORT));

        // External source
        createExternalClientConfig();
    }

    private void createExternalClientConfig(){
        final String host = Utils.retrieveLocalIpAddress();
        final int port = SettingsUtils.getInstance().parseFromPreferencesAisMessagesClientPort();

        SettingsUtils.getInstance().setToPreferencesOwnIp(host!=null? host : "No network connection");

        final DatagramSocketConfig client=createDatagramSocketConfig(host,port);
        if (client!=null) {
            clients.put(Source.EXTERNAL,client);
        }
    }

    private List<DatagramSocketConfig> createRepeaterConfigs() {
        List<DatagramSocketConfig> result=new ArrayList<>();

        final String host1 = SettingsUtils.getInstance().parseFromPreferencesAisMessagesDestinationHost1();
        final int port1 = SettingsUtils.getInstance().parseFromPreferencesAisMessagesDestinationPort1();
        final DatagramSocketConfig config1=createRepeaterConfig(host1,port1);
        if (config1!=null){
            result.add(config1);
            Analytics.logEvent(this,Analytics.CATEGORY_NMEA_REPEAT, "RepeatNMEA_Config1",String.valueOf(config1));
        }

        final String host2 = SettingsUtils.getInstance().parseFromPreferencesAisMessagesDestinationHost2();
        final int port2 = SettingsUtils.getInstance().parseFromPreferencesAisMessagesDestinationPort2();
        final DatagramSocketConfig config2=createRepeaterConfig(host2,port2);
        if (config2!=null){
            result.add(config2);
            Analytics.logEvent(this,Analytics.CATEGORY_NMEA_REPEAT, "RepeatNMEA_Config2",String.valueOf(config2));
        }

        return result;
    }

    private DatagramSocketConfig createRepeaterConfig(final String host,final int port){
        final String tag="createRepeaterConfig - ";

        String informText;
        DatagramSocketConfig result=null;

        DatagramSocketConfig repeater=createDatagramSocketConfig(host,port);
        if (repeater!=null){
            if (!repeater.equals(clients.get(Source.INTERNAL)) && !repeater.equals(clients.get(Source.EXTERNAL))){
                result=repeater;
                informText="Repeating NMEA messages to: "+host+":"+port+".";
            } else {
                informText="Not allowed to repeat to address: "+host+":"+port+". Creating loop.";
            }
        } else {
            informText="Invalid repeater settings: "+host+":"+port+".";
        }

        Log.d(TAG,tag+informText);
        if (port>0) {
            // When port is 0, asked to disable this repeater: Be quiet about this.
            Toast.makeText(this, informText, Toast.LENGTH_LONG).show();
        }

        return result;
    }

    private static DatagramSocketConfig createDatagramSocketConfig(final String host, final int port){
        final String tag="createDatagramSocketConfig - ";

        DatagramSocketConfig result=null;

        // Validate loop, not empty
        if ((port > 0) && host!=null && !host.isEmpty()) {
            try {
                // Ignore result, only interested whether an UnknownHostException is thrown.
                //noinspection ResultOfMethodCallIgnored
                InetAddress.getByName(host);
                result=new DatagramSocketConfig(host, port);
            } catch (UnknownHostException e) {
                Log.w(TAG, tag + "Invalid host: " + host, e);
            }
        }
        return result;
    }

    private void createAndStartNmeaUdpClientTasks() {
        for (final Source source : SOURCES) {
            createAndStartNmeaUdpClientTask(source);
        }
    }

    private void createAndStartNmeaUdpClientTask(final Source source) {
        final String tag = "createAndStartNmeaUdpClientTask - ";

        NmeaUdpClientTask task = nmeaUdpClientTasks.get(source);
        if (task == null) {
            Log.d(TAG, tag + "Creating new NmeaUdpClient");
            final DatagramSocketConfig config = clients.get(source);
            if (config != null) {
                // Create and start tasks when there is a config available
                task = new NmeaUdpClientTask(this, source, config);
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                nmeaUdpClientTasks.put(source, task);
            }
        } else {
            Log.d(TAG, tag + "Using existing NmeaUdpClient");
        }
    }

	public boolean addListener(ShipReceivedListener listener) {
		synchronized(listeners){
			return listeners.add(listener);
		}	
	}
	
	public boolean removeListener(ShipReceivedListener listener) {
		synchronized(listeners){
			return listeners.remove(listener);
		}	
	}

    @Override
	public synchronized void onNmeaReceived(final String nmea,final Source source) {
		Log.v(TAG,"onNmeaReceived - nmea: "+nmea+", source: "+source);

		// Convert NMEA to Ship
        final Ship ship = nmea2Ship.onMessage(nmea,source);
        if (ship != null && ship.isValid()) {
            if (Source.INTERNAL.equals(source) && !Utils.haveNetworkConnection(this)){
                // Add to cache when NMEA is from own source (dongle) and there is no network connection
                cache.add(nmea);
            }

            final Set<Integer> mmsiReceivedSrc=mmsiReceived.get(source);
            if (mmsiReceivedSrc!=null) {
                mmsiReceivedSrc.add(ship.getMmsi());
            }

            final Boolean mustRepeatFromSrc=mustRepeatFrom.get(source);
            if (mustRepeatFromSrc!=null && mustRepeatFromSrc){
                if (repeater!=null) {
                    repeater.repeat(nmea);
                }
            }

            synchronized (listeners) {
                for (final ShipReceivedListener listener : listeners) {
                    listener.onShipReceived(ship);
                }
            }
        }
	}

	public class ServiceBinder extends Binder {
		public NmeaClientService getService() {
			return NmeaClientService.this;
		}
	}

    private BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                final Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    final String nmeasRaw = bundle.getString(MyFirebaseMessagingService.LOCAL_BROADCAST_DATA);
                    if (nmeasRaw != null) {
                        final String[] nmeas = nmeasRaw.split(Pattern.quote(MyFirebaseMessagingRepeater.NMEA_SEP));
                        for (final String nmea : nmeas) {
                            if (!nmea.isEmpty()) {
                                onNmeaReceived(MyFirebaseMessagingRepeater.PREFIX_AIVDM + nmea, Source.CLOUD);
                            }
                        }
                    }
                }
            }
        }
    };

    public class ConnectivityChangeReceiver extends BroadcastReceiver {

        /**
         * Executed on change, so either there is or is not a connection.
         */
        @Override
        public void onReceive(final Context context, final Intent intent) {

            if (Utils.haveNetworkConnection(context)) {
                // Now we have a connection, try to start external NMEA client
                createExternalClientConfig();
                createAndStartNmeaUdpClientTask(Source.EXTERNAL);

                if (cache != null) {
                    cache.processCachedMessages();
                }
            }
        }
    }
}

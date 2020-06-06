package net.videgro.ships;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdRequest.Builder;
import com.google.android.gms.ads.AdView;

import net.videgro.ships.activities.MainActivity;
import net.videgro.ships.listeners.ImagePopupListener;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

public final class Utils {
	private static final String TAG = "Utils";

	public static final Long IMAGE_POPUP_AUTOMATIC_DISMISS=1000*5L;

	private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("[HH:mm:ss] ", Locale.getDefault());


	private Utils(){
		// Utility class, no public constructor
	}
	
	public static boolean haveNetworkConnection(Context context) {
	    boolean haveConnectedWifi = false;
	    boolean haveConnectedMobile = false;

	    final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
	    if (cm!=null) {
			NetworkInfo[] netInfo = cm.getAllNetworkInfo();
			for (NetworkInfo ni : netInfo) {
				if (ni.getTypeName().equalsIgnoreCase("WIFI")) {
					if (ni.isConnected()) {
						haveConnectedWifi = true;
					}
				}
				if (ni.getTypeName().equalsIgnoreCase("MOBILE")) {
					if (ni.isConnected()) {
						haveConnectedMobile = true;
					}
				}
			}
		}
	    return haveConnectedWifi || haveConnectedMobile;
	}

	public static String retrieveLocalIpAddress() {
		try {
			for (final Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				final NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					final InetAddress inetAddress = enumIpAddr.nextElement();

					if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
						return inetAddress.getHostAddress();
					}
				}
			}
		} catch (SocketException e) {
			Log.e("IP Address", e.toString());
		}
		return null;
	}

	public static void loadAd(final View view){

		// Create bundle to set non-personalized ads
		final Bundle extras = new Bundle();
		extras.putString("npa", "1");

		final Builder builder = new AdRequest.Builder();
		builder.addNetworkExtrasBundle(AdMobAdapter.class, extras); // Add bundle to builder
		builder.addTestDevice(AdRequest.DEVICE_ID_EMULATOR); // Emulator

		// Add test devices
		final String[] testDevices = view.getContext().getString(R.string.testDevices).split(",");		
	    for (final String testDevice:testDevices){
	    	builder.addTestDevice(testDevice);
	    }
	    
	    final AdView adView = (AdView) view.findViewById(R.id.adView);
	    adView.loadAd(builder.build());
	}

	public static void logStatus(final Activity activity,final TextView textView,final String status) {
		final String tag="logStatus - ";
		Log.d(TAG,tag+status);
		if (activity!=null){
			final String text = LOG_TIME_FORMAT.format(new Date()) + status;
			updateText(activity,textView,text);
		} else {
			Log.e(TAG,tag+"Huh? No activity set. ("+status+")");
		}
	}

	private static void updateText(final Activity activity,final TextView textView,final String text) {
		final String tag="updateText - ";
		final int maxLines=100;
		if (activity!=null){
			activity.runOnUiThread(new Runnable() {
				public void run() {
					final String[] lines=textView.getText().toString().split("\n");
					StringBuilder txt=new StringBuilder();
					txt.append(text);
					for (int i=0;i<maxLines && i<lines.length;i++){
						txt.append("\n").append(lines[i]);
					}
					textView.setText(txt);
				}
			});
		} else {
			Log.e(TAG,tag+"Huh? No activity set. ("+text+")");
		}
	}

	public static void showPopup(final int id,final Activity activity,final ImagePopupListener listener,final String title,final String message,final int imageResource,final Long automaticDismissDelay){
		final String tag="showPopup - ";

		activity.runOnUiThread(new Runnable() {
		    public void run() {
		    	final AlertDialog.Builder ad = new AlertDialog.Builder(activity);
				ad.setTitle(title);
				ad.setMessage(Html.fromHtml(message));
				ad.setIcon(imageResource);
				ad.setNeutralButton("OK", new DialogInterface.OnClickListener(){
					@Override
					public void onClick(DialogInterface arg0, int arg1) {
						if (listener!=null){
							listener.onImagePopupDispose(id);
						}
					}
				});

				final AlertDialog alert = ad.create();
				alert.show();

				// Make the textview clickable. Must be called after show()
				((TextView)alert.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());

				if (automaticDismissDelay != null) {
					final Handler handler = new Handler();
					final Runnable runnable = new Runnable() {
						public void run() {
							if (alert.isShowing()) {
								try {
									alert.dismiss();
								} catch (IllegalArgumentException e) {
									// FIXME: Ugly fix (View not attached to window manager)
									Log.e(TAG,tag+"Auto dismiss", e);
								}
							}
						}
					};
					handler.postDelayed(runnable, automaticDismissDelay);
				}
		    }
		});		
	}

	public static void showQuestion(final String labelPositive,final String labelNegative,final int idPositive,final int idNegative,final Activity activity,final ImagePopupListener listener,final String title,final String message,final int imageResource){
		final String tag="showQuestion - ";

		activity.runOnUiThread(new Runnable() {
			public void run() {
				final AlertDialog.Builder ad = new AlertDialog.Builder(activity);
				ad.setTitle(title);
				ad.setMessage(Html.fromHtml(message));
				ad.setIcon(imageResource);
				ad.setPositiveButton(labelPositive, new DialogInterface.OnClickListener(){
					@Override
					public void onClick(DialogInterface arg0, int arg1) {
						if (listener!=null){
							listener.onImagePopupDispose(idPositive);
						}
					}
				});
				ad.setNegativeButton(labelNegative, new DialogInterface.OnClickListener(){
					@Override
					public void onClick(DialogInterface arg0, int arg1) {
						if (listener!=null){
							listener.onImagePopupDispose(idNegative);
						}
					}
				});

				final AlertDialog alert = ad.create();
				alert.show();

				// Make the textview clickable. Must be called after show()
				((TextView)alert.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
			}
		});
	}

	public static boolean is64bit(){
		final String VAL_64="64";
		// API level 21+ use Build.SUPPORTED_64_BIT_ABIS
		return (android.os.Build.CPU_ABI!=null && android.os.Build.CPU_ABI.contains(VAL_64)) || (android.os.Build.CPU_ABI2!=null && android.os.Build.CPU_ABI2.contains(VAL_64));
	}

    public static String retrieveAbi(){
        return ((android.os.Build.CPU_ABI!=null) ? android.os.Build.CPU_ABI:"")+((android.os.Build.CPU_ABI2!=null) ? " - "+android.os.Build.CPU_ABI2:"");
    }
}

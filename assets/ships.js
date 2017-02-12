function pad(n, width, z) {
	z = z || '0';
	n = n + '';
	return n.length >= width ? n : new Array(width - n.length + 1).join(z) + n;
}

function timestamp2prettyPrint(timestamp) {
	var a = new Date(timestamp);
	var months = [ 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug','Sep', 'Oct', 'Nov', 'Dec' ];
	var year = a.getFullYear();
	var month = months[a.getMonth()];
	var date = a.getDate();
	var hour = pad(a.getHours(), 2);
	var min = pad(a.getMinutes(), 2);
	var sec = pad(a.getSeconds(), 2);
	var time = date + ' ' + month + ' ' + year + ' ' + hour + ':' + min + ':' + sec;
	return time;
}

function addShip(ship) {
	ship.lastUpdated=new Date().getTime(); // Add extra field
	ships[mmsiKey(ship)]=ship;
	
	// Create new marker
	var lonLat = new OpenLayers.LonLat(ship.lon, ship.lat).transform(new OpenLayers.Projection("EPSG:4326"), map.getProjectionObject());
	
	var popupText = "<table>";
	popupText += "<tr><td colspan='2'><h3><a href='PLACEHOLDER_MMSI"+ship.mmsi+"'><img class='flag' src='file:///android_asset/images/flags/"+ship.countryFlag+".png'>"+ship.name+ " " + ship.mmsi +"</a></h3></td></tr>";
	popupText += "<tr><td>Country:</td><td>" + ship.countryName + "</td></tr>";
	popupText += "<tr><td>Callsign:</td><td>" + ship.callsign + "</td></tr>";
	popupText += "<tr><td>Ship type:</td><td>" + ship.shipType + "</td></tr>";
	popupText += "<tr><td>Destination:</td><td>" + ship.dest + "</td></tr>";
	popupText += "<tr><td>Nav. status:</td><td>" + ship.navStatus+ "</td></tr>";
	popupText += "<tr><td>Speed:</td><td>" + ship.sog + "</td></tr>";
	popupText += "<tr><td>Heading:</td><td>" + ship.heading +"</td></tr>";
	popupText += "<tr><td>Course:</td><td>" + (ship.cog / 10).toFixed(1)+ "</td></tr>";	
//	popupText += "<tr><td><h3>Position</h3></td><td/></tr>";
//	popupText += "<tr><td> - Latitude:</td><td>" + ship.lat + "</td></tr>";
//	popupText += "<tr><td> - Longitude:</td><td>" + ship.lon + "</td></tr>";
//	popupText += "<tr><td><h3>Dimensions</h3></td><td/></td></tr>";
//	popupText += "<tr><td> - Bow:</td><td>" + ship.dimBow + "</td></tr>";
//	popupText += "<tr><td> - Port:</td><td>" + ship.dimPort + "</td></tr>";
//	popupText += "<tr><td> - Starboard:</td><td>" + ship.dimStarboard+ "</td></tr>";
//	popupText += "<tr><td> - Stern:</td><td>" + ship.dimStern + "</td></tr>";
	popupText += "<tr><td>Time:</td><td>"+ timestamp2prettyPrint(ship.timestamp) + " ("	+ (new Date().getTime() - ship.timestamp) + ")</td></tr>";
	popupText += "</table>";

	// "rot":
	// specialManIndicator
	// subMessage
	// "draught":0,

	var angle = Math.round(ship.cog / 10);
	var origin = new OpenLayers.Geometry.Point(lonLat.lon, lonLat.lat);
	var name=(ship.name=="")?ship.mmsi:ship.name;
	
	// Default: 110 x 11 m
	var width=   ((ship.dimBow!="" && ship.dimStern!="")?parseInt(ship.dimBow)+parseInt(ship.dimStern):110)*.8; // = real length
	var height=  ((ship.dimStarboard!="" && ship.dimPort!="")?parseInt(ship.dimStarboard)+parseInt(ship.dimPort):11)*8; // = real width
	
	var shipFeature1 = new OpenLayers.Feature.Vector(
		// +90 degrees because icon is pointing to the left instead of top
		origin, {
			angle : angle + 90,
			opacity : 100,
			name : name,
			width: width,
			height: height,
			fontColor: 0,
			message : popupText
	})
	
	var pts = new Array(origin, new OpenLayers.Geometry.Point(lonLat.lon,lonLat.lat + ((1 + ship.sog) * SPEED_FACTOR)));

	var shipFeature2 = new OpenLayers.Feature.Vector(new OpenLayers.Geometry.LineString(pts), {name : ""	});
	// Rotation angle in degrees (measured counterclockwise from the positive x-axis)
	shipFeature2.geometry.rotate(angle * -1, origin);

	var shipFeatures = [ shipFeature2, shipFeature1 ];
	shipVectors.addFeatures(shipFeatures);

	// We know this ship already. Put marker at new position and draw trace
	var previousMarkers = markers[mmsiKey(ship)];
	if (previousMarkers != null) {

		// Remove previous marker
		shipVectors.removeFeatures(previousMarkers);

		// Create trace line from previous to new position
		var points = new Array(previousMarkers[0].geometry.getCentroid(),new OpenLayers.Geometry.Point(lonLat.lon, lonLat.lat));

		var line = new OpenLayers.Geometry.LineString(points);

		var strokeColor = pad(ship.mmsi, 6);
		var strokeColor = strokeColor.substring(strokeColor.length - 7,	strokeColor.length - 1);
		// console.log("strokeColor: "+strokeColor);

		var lineFeature = new OpenLayers.Feature.Vector(line, null,{
			strokeColor : '#' + strokeColor,
			strokeOpacity : 0.5,
			strokeWidth : 2
		});
		
		lineLayer.addFeatures([ lineFeature ]);
		traces[new Date().getTime()] = lineFeature;
	}
	
	if (ship.name!="" && typeof shipsNamePlayed[mmsiKey(ship)] === 'undefined') {
		// console.log("Play sound");

		var gongListener = function(event) {
			audioName.src = "audio/" + mmsiKey(ship) + ".wav";
			//console.log("Play: " + audioName.src);
			audioName.play();

			document.querySelector("#audioGong").removeEventListener("ended",gongListener);
		}

		if (ship.audioAvailable){
			document.querySelector("#audioGong").addEventListener("ended",gongListener);
		}
		
		audioGong.play();
		shipsNamePlayed[mmsiKey(ship)] = true;
	}

	// Replace/add new marker to map of markers
	markers[mmsiKey(ship)] = shipFeatures;
}

function autoZoom(){
	var ext=shipVectors.getDataExtent();
	if (ext!=null){
		// Extend data extent with layerMyPosition-data extent
		ext.extend(layerMyPosition.getDataExtent());
	} else {
		ext=layerMyPosition.getDataExtent();
	}
	
	if (ext!=null){
		map.zoomToExtent(ext);
		map.baseLayer.redraw();
	}
}

function cleanup() {
	// Remove after 20 minutes:
	// - Old markers (ship+speed)
	// - Old traces

	var now = new Date().getTime();
	var maxAge = (1000*60*20); // 20 minutes
	
	// Remove ships
	for (keyMmsi in ships) {
	    if (ships.hasOwnProperty(keyMmsi)) {	
	    		var ship=ships[keyMmsi];
	    		var timestamp=ship.lastUpdated;
	    		
	    		var age=(now-timestamp);
	    		
	    		if (age>(maxAge/2)){
	    			// Indicating ship will be removed in the (near) future
	    			// Change label color on second feature (index 1)
		    		markers[keyMmsi][1].attributes.fontColor='#FF0000';	   
		    		shipVectors.redraw();
		    		
		    		if (age>maxAge){
		    			console.log("Removing ship: "+ship.mmsi+" ("+ship.name+"), Timestamp: "+timestamp+" (Age: "+age+")");
		    			
		    			// Remove ship markers
		    			shipVectors.removeFeatures(markers[keyMmsi]);
		    			
		    			// Remove ship from administration	    			
		    			delete markers[keyMmsi];
		    			delete shipsNamePlayed[keyMmsi];
		    			delete ships[keyMmsi];
		    			
		    			printStatistics();
		    		}
	    		}
	    }
	}	
	  
    // ^0$|^[1-9]\d*$/.test(keyTimestamp) &&    
    // keyTimestamp <= 4294967294          
	
	// Remove traces
	for (keyTimestamp in traces) {
	    if (traces.hasOwnProperty(keyTimestamp)){	
	    		if ((now-keyTimestamp)>maxAge){
	    			//console.log("Removing trace - Timestamp: "+keyTimestamp+" (Age: "+(now-keyTimestamp)+")");
	    			
	    			// Remove trace
	    			lineLayer.removeFeatures(traces[keyTimestamp]);
	    			
	    			// Remove trace from administration
	    			delete traces[keyTimestamp];	    			
	    		}
	    	}
	}
}

function printStatistics(){
	console.log("# Ships: "+Object.keys(ships).length);
	console.log("# Markers: "+Object.keys(markers).length);
	console.log("# Ships (name said): "+Object.keys(shipsNamePlayed).length);	
	console.log("# Traces: "+Object.keys(traces).length);
}

function calculateAndPrefetchTileUrl(bounds) {	
	var result=null;	

	// When not set to prefetch tiles: Use as minimum zoom, the current zoom level. In this way the for-loop will just run once.
	// Otherwise use 1, so that lower zoom levels from the current to the whole world view will be fetched.
	var minimumZoom=prefetchLowerZoomLevelsTiles ? 1 : this.map.getZoom();
	
	for (zoom=this.map.getZoom();zoom>=minimumZoom;zoom--){
		var path=calculateTilePath(bounds,this.map.getResolution(),this.maxExtent,this.tileSize,zoom,this.type);
	
		if (path!=null){
			var url=this.url;
			if (url instanceof Array) {
				url = this.selectUrl(path, url);
			}
			
			var source=url+path;

			if (zoom==this.map.getZoom()){
				result=source;
			} else {
				var imageId=((new Date().getMilliseconds())*1000)+zoom;
				prefetchedTiles[imageId]=new Image();
				prefetchedTiles[imageId].onload = function(){
					//console.log("calculateAndPrefetchTileUrl - image loaded: "+this.src);
				};
				prefetchedTiles[imageId].src=source;
			}
		}
	}

	return result;
}

function calculateTilePath(bounds,res,maxExtent,tileSize,z,typ) {
	var result=null;	
	
	var x = Math.round((bounds.left - maxExtent.left) / (res * tileSize.w));
	var y = Math.round((maxExtent.top - bounds.top) / (res * tileSize.h));

	var limit = Math.pow(2, z);
	if (y < 0 || y >= limit) {
		result=null;
	} else {
		x = ((x % limit) + limit) % limit;
		
		// Path
		result=z + "/" + x + "/" + y + "." + typ;
	}
	return result;
}

var mmsiKey = function(obj) {
	// some unique object-dependent key
	return obj.mmsi;
};

function createMap(){
	map = new OpenLayers.Map("mapdiv", {
		projection : new OpenLayers.Projection("EPSG:900913"),
		displayProjection : new OpenLayers.Projection("EPSG:4326"),
		controls : [ new OpenLayers.Control.Navigation(),
				new OpenLayers.Control.ScaleLine({
					topOutUnits : "nmi",
					bottomOutUnits : "km",
					topInUnits : 'nmi',
					bottomInUnits : 'km',
					maxWidth : '40'
				}), new OpenLayers.Control.LayerSwitcher(),
				new OpenLayers.Control.MousePosition(),
				new OpenLayers.Control.PanZoomBar(),
				new OpenLayers.Control.TouchNavigation()],
		numZoomLevels : ZOOM_LEVELS,
		maxResolution : 156543,
		units : 'meters'
	});
}

function createStyleMapShipSymbol(){
  	styleMapShipSymbol = new OpenLayers.StyleMap({
  		"default" : new OpenLayers.Style({
  			externalGraphic : "file:///android_asset/images/container-ship-top.png",
  			graphicWidth : "${width}",
  			graphicHeight: "${height}",
  			// graphicXOffset: -40,
  			// graphicYOffset: -40,
  			rotation : "${angle}",
  			fillOpacity : "${opacity}",
  			label : "${name}",
  			fontColor : "${fontColor}",
  			fontSize : "12px",
  			fontFamily : "Courier New, monospace",
  			fontWeight : "bold",
  			labelAlign : "left",
  			labelXOffset : "0",
  			labelYOffset : "-50",
  			labelOutlineColor : "white",
  			labelOutlineWidth : 3,
  			strokeColor : "#00FF00",
  			strokeOpacity : 1,
  			strokeWidth : 3,
  			fillColor : "#FF5500"
  		}),
  		
  		"select" : new OpenLayers.Style({
  			cursor : "crosshair",
  		})
  	});
}

function createLayers(){
	// Layer: My position
	layerMyPosition = new OpenLayers.Layer.Markers("My position");
	map.addLayer(layerMyPosition);
	
	// Layer: OSM
	//var layerOsm = new OpenLayers.Layer.OSM("OpenStreetMap",
	//[ 'http://127.0.0.1:8181/a.tile.openstreetmap.org/${z}/${x}/${y}.png'], null);
	//["http://"+TILE_PROXY_URL+"a.tile.openstreetmap.org/","http://"+TILE_PROXY_URL+"b.tile.openstreetmap.org/","http://"+TILE_PROXY_URL+"c.tile.openstreetmap.org/"]
	
	var layerOsm = new OpenLayers.Layer.OSM("OpenStreetMap",
			"http://"+TILE_PROXY_URL+"a.tile.openstreetmap.org/", {
				numZoomLevels : ZOOM_LEVELS,
				type : 'png',
				getURL : calculateAndPrefetchTileUrl,
				isBaseLayer : true,
				displayOutsideMaxExtent : true
			});
	
	var layerSeamark = new OpenLayers.Layer.TMS("OpenSeaMap",
			"http://"+TILE_PROXY_URL+"tiles.openseamap.org/seamark/", {
				numZoomLevels : ZOOM_LEVELS,
				type : 'png',
	  				getURL : calculateAndPrefetchTileUrl,
	  				isBaseLayer : false,
	  				displayOutsideMaxExtent : true
	  			});
 	map.addLayers([ layerOsm, layerSeamark ]);
}

function createLayerShips(){
	// Layer: Ships
  	shipVectors = new OpenLayers.Layer.Vector("Ships", {
  		eventListeners : {
  			'featureclick' : function(evt) {
  				console.log("featureselected");
  				
  				var feature = evt.feature;
  	
  				if (typeof feature.attributes.message !== 'undefined' && feature.attributes.message != "") {
  					// Must create a popup on ship symbol
  					var popup = new OpenLayers.Popup.FramedCloud("popup",OpenLayers.LonLat.fromString(feature.geometry.toShortString()),new OpenLayers.Size(200,800),feature.attributes.message, null, true, null);
  					//popup.autoSize = true;
  					//popup.maxSize = new OpenLayers.Size(20,80);
  					//popup.fixedRelativePosition = true;
  					
  					feature.popup = popup;
  					map.addPopup(popup,true);
  				}
  			},
  			'featureunselected' : function(evt) {
  				console.log("featureunselected");
  				
  				var feature = evt.feature;
  				if (feature.popup != null) {
  					map.removePopup(feature.popup);
  					feature.popup.destroy();
  					feature.popup = null;
  				}
  			}
  		},
  		styleMap : styleMapShipSymbol
  	});
  	
  	map.addLayers([shipVectors]);
}

function createControls(){
  	var selectControl = new OpenLayers.Control.SelectFeature(shipVectors, {hover : true});
  	map.addControl(selectControl);
  	selectControl.activate();
}

function createLayerTraces(){
  	// Layer: Traces
  	lineLayer = new OpenLayers.Layer.Vector("Ship traces");
  	map.addLayer(lineLayer);
  	map.addControl(new OpenLayers.Control.DrawFeature(lineLayer,OpenLayers.Handler.Path));
}

function createZoomAction(){
  	// Zoom (in/out) ships
  	var zoomSquared=ZOOM_LEVELS*ZOOM_LEVELS;
  	map.events.register("zoomend", map, function() {
  	        // http://gis.stackexchange.com/questions/31943/how-to-resize-a-point-on-zoom-out
  	        //var new_style = OpenLayers.Util.extend({}, OpenLayers.Feature.Vector.style['default']);
  	        var stle=styleMapShipSymbol.styles['default'].defaultStyle;
  	        stle.pointRadius=((45/zoomSquared)*map.getZoom()*map.getZoom());
  	        //stle.graphicWidth=((75/zoomSquared)*map.getZoom()*map.getZoom());
  	        //stle.fontSize=((12/zoomSquared)*map.getZoom()*map.getZoom());
  	        //shipVectors.redraw();
  	        
  	        map.baseLayer.redraw();
  	});
}

// Called from Java
function setCurrentPosition(lon,lat){
	var lonLat = new OpenLayers.LonLat(lon, lat).transform(new OpenLayers.Projection("EPSG:4326"), map.getProjectionObject());
	
	if (previousMyPositionMarker!=null){
		previousMyPositionMarker.destroy();
	}
	
	var size = new OpenLayers.Size(50, 50);
	var offset = new OpenLayers.Pixel(-(size.w/2), -(size.h/2)); // Middle
	//var icon = new OpenLayers.Icon('http://www.openstreetmap.org/openlayers/img/marker.png',size,offset);
	var icon = new OpenLayers.Icon('file:///android_asset/images/sailboat-black-side.png',size,offset);

	previousMyPositionMarker=new OpenLayers.Marker(lonLat,icon);
	layerMyPosition.addMarker(previousMyPositionMarker);
	
	if (zoomToExtent) {
		autoZoom();
	}
}

// Incoming data
// Called from Java
function onShipReceived(data){
    //$('#messages').append(data).append("<br />");
	addShip(JSON.parse(data));
	cleanup();
		
	if (!init || zoomToExtent) {
		init = true;		
		autoZoom();
	}
}

// Called from Java
function setZoomToExtent(zoomToExtentIn){
	zoomToExtent=zoomToExtentIn;
}

//Called from Java
function setPrefetchLowerZoomLevelsTiles(prefetchLowerZoomLevelsTilesIn){
	prefetchLowerZoomLevelsTiles=prefetchLowerZoomLevelsTilesIn;
}

/*************************************************************************************************************************** */

const TILE_PROXY_URL="127.0.0.1:8181/";
const ZOOM_LEVELS=18;
const SPEED_FACTOR=25;

var ships = {};
var shipsNamePlayed = {};
var markers = {};
var traces = {};
var prefetchedTiles=new Array(); // Array of images
var zoomToExtent=false;
var prefetchLowerZoomLevelsTiles=true;
var init = false;
var previousMyPositionMarker=null;
var map;
var shipVectors;
var layerMyPosition;
var styleMapShipSymbol;
var lineLayer;





/**
 * Based off of the ui_cesium_smoke.js class written by NASA engineers to
 * connect the SmokeServiceActor to the Cesium front-end.
 */

/**
 * Import statements ui_cesium_smoke.js
 */
import * as config from "./config.js"; // generated by scala
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

// constant of the fire voice layer for initial rendering
const defaultContourRender = initDefaultColors(config.fireVoiceLayer.countourRender);
// var to change the display
var currentContourRender = initDefaultColors(config.fireVoiceLayer.countourRender);

/**
 * Constants adapted from ui_cesium_smoke.js
 */
const FireVoiceLayerType = {PERIM : "perim", TEXT:"text"}
const LOADED = "○";
const SHOWING = "●"

/**
 * Adapted from ui_cesium_smoke.js
 * Each entry represents a satellite image
 * Creates a new entry for the fire voice layer (smoke layer in cesium_smoke)
 */
class Entry{
  static create (fireVoiceLayer){
    return new Entry(fireVoiceLayer);
  }

  // This needs to be changed with the FireVoiceLayer class objects
  /**
   static compareFiltered (a,b) { // used to make the entry list stay in order
   switch (util.compare(a.date,b.date)) {
   case -1: return 1;
   case 0: return util.compare(a.satellite, b.satellite); // this can't be used
   case 1: return -1;
   }
   */
  constructor(fireVoiceLayer) {
    this.id = fireVoiceLayer.uniqueId;
    this.date = fireVoiceLayer.date;
    // this.satellite = fireVoiceLayer.satellite;
    // this.srs = fireVoiceLayer.srs;
    this.perimEntry = FireVoiceEntry.create(fireVoiceLayer, fireVoiceLayerType.PERIM); //GeoJson
    // This has to be represented within a label to  visualize the text on the map
    this.textEntry = FireVoiceEntry.create(fireVoiceLayer, fireVoiceLayerType.TEXT); // Text
  }

  /**
   * This is the function that is called when the user clicks on the entry
   * It clears the current entry and sets the visibility to false
   */
  clear() {
    this.perimEntry.SetVisible(false);
    this.textEntry.SetVisible(false);
    uiCesium.requestRender();
  }

  /**
   * Updates the render for both layers of an entry
   */
  renderChanged () {
    this.perimEntry.renderChanged();
    this.textEntry.renderChanged();
  }
}

/**
 * FireVoiceEntry class - parent class for stored perim and text layers
 */
class FireVoiceEntry {
  static create (fireVoiceLayer, type) {
    if (type == fireVoiceLayerType.PERIM) return new FirePerimEntry(fireVoiceLayer);
    if (type == fireVoiceLayerType.TEXT) return new FireTextEntry(fireVoiceLayer);

  }

  /**
   * From the JSON that is pushed over the websocket we get this information
   * @param fireVoiceLayer - the JSON object that is pushed over the websocket
   * @param type - the type of layer (perim or text)
   */
  constructor(fireVoiceLayer, type) {
    this.date = fireVoiceLayer.date;
    // this.satellite = fireVoiceLayer.satellite;  probably uses satellite for the geojson layer but what about the text layer?
    // this.srs = fireVoiceLayer.srs;
    this.url = undefined;
    this.dataSource = undefined;
    this.render = {...currentContourRender};
    this.show = false;
  }

  setStatus (newStatus) {
    this.status = newStatus;
  }

  setVisible (showIt) {
    if (showIt !=  this.show) {
      this.show = showIt;
      if (showIt) {
        if (!this.dataSource) {
          this.loadContoursFromUrl();
        } else {
          this.dataSource.show = true;
          uiCesium.requestRender();
        }
        this.setStatus( SHOWING);
      }
    }
    if (showIt == false) {
      if (this.dataSource) {
        this.dataSource.show = false;
        uiCesium.requestRender();
        this.setStatus( LOADED);
      }
    }
  }
}



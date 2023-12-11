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
  static create(fireVoiceLayer, type) {
    if (type == fireVoiceLayerType.PERIM) return new FirePerimEntry(
        fireVoiceLayer);
    if (type == fireVoiceLayerType.TEXT) return new FireTextEntry(
        fireVoiceLayer);

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

  /**
   * Sets the visibility of the layer
   * @param newStatus - boolean value of whether or not the layer is visible
   */
  setStatus(newStatus) {
    this.status = newStatus;
  }

  /**
   * Sets the visibility of the layer
   * @param showIt - boolean value of whether or not the layer is visible
   */
  setVisible(showIt) {
    if (showIt != this.show) {
      this.show = showIt;
      if (showIt) {
        if (!this.dataSource) {
          this.loadContoursFromUrl();
        } else {
          this.dataSource.show = true;
          uiCesium.requestRender();
        }
        this.setStatus(SHOWING);
      }
    }
    if (showIt == false) {
      if (this.dataSource) {
        this.dataSource.show = false;
        uiCesium.requestRender();
        this.setStatus(LOADED);
      }
    }
  }

  /**
   * Loads the contours from the url
   * Can we use the same function as cesium_smoke since they deal with the map in a similar way?
   * @returns {Promise<void>}
   */
  async loadContoursFromUrl() { // handles new data source
    let renderOpts = this.getRenderOpts(); // get rendering options
    //console.log("@@DEBUG ", this.url);
    let response = await fetch(this.url); // pulls data from the server hosting it - see route definition in service
    let data = await response.json();
    Cesium.GeoJsonDataSource.load(data, renderOpts).then(  // loads data into a cesium data source object
        ds => {
          //console.log("@@DEBUG got data", this.url);
          this.dataSource = ds;
          this.postProcessDataSource(); // updates fill colors
          uiCesium.addDataSource(ds); // adds data source to cesium
          uiCesium.requestRender(); // updates the render
        }
    );
  }

  /**
   * Gets the render options for the layer
   * @returns {{strokeWidth: (number|string|*), alpha: *, stroke: *, clampToGround: boolean}} - the render options
   */
  getRenderOpts() { // provides default render options
    return {
      stroke: this.render.strokeColor,
      strokeWidth: this.render.strokeWidth,
      alpha: this.render.alpha,
      clampToGround: false
    };
  }

  /**
   * Post processes the entities or polygons in the data source
   * Changed from cesium_smoke to only handle the perim layer since the text layer is a label
   * Empty and will be overridden by the perim child class
   */
  postProcessDataSource() {
    /**
      postProcessDataSource() { // post processes the entities or polygons in the data source
    let entities = this.dataSource.entities.values;
    let render = this.render;
    for (const e of entities) { // update each entities color to match smoke/cloud colors
      if (this.type == fireVoiceLayerType.PERIM) {
        e.polygon.material = this.render.smokeColor;
      }

      // TODO: Change this rendering (need to be for text)
      if (this.type == fireVoiceLayerType.TEXT) {
        e.polygon.material = this.render.cloudColor;
      }
      e.polygon.outline = true;
      e.polygon.outlineColor = this.render.strokeColor;
      e.polygon.outlineWidth = this.render.strokeWidth;
    }
  }
  */
  }

  /**
   * Displays the text on the map
   * Empty and will be overridden by the text child class
   */
  displayText() {
  }

  /**
   * Updates the render parameters according to user input
   */
  renderChanged() {
    this.render = {...currentContourRender}; // updates render parameters
    this.getRenderOpts();
    if (this.dataSource) {
      this.postProcessDataSource(); // updates data fill
      uiCesium.requestRender(); // requests new render
    }
  }
}

  /**
   *
   */
  class FirePerimEntry extends FireVoiceEntry {
  constructor(fireVoiceLayer) {
      super(fireVoiceLayer);
      this.smokeFile = fireVoiceLayer.smokeFile;
      this.url = fireVoiceLayer.smokeUrl;
      this.type = fireVoiceLayerType.PERIM;
    }

    postProcessDataSource() {
      let entities = this.dataSource.entities.values;
      for (const e of entities) { // update each entities color to match smoke/cloud colors
        e.polygon.material = this.render.smokeColor;
        e.polygon.outline = true;
        e.polygon.outlineColor = this.render.strokeColor;
        e.polygon.outlineWidth = this.render.strokeWidth;
      }
    }
  }

/**
 * FireTextEntry class - child class for text layer
 * This is the class that will be used to display the text on the map
 */
class FireTextEntry extends FireVoiceEntry {
  /**
   * Constructor for the text layer
   * TODO: Could be missing elements
   * @param fireVoiceLayer
   */
    constructor(fireVoiceLayer) {
      super(fireVoiceLayer, fireVoiceLayerType.TEXT);
      this.textFile = fireVoiceLayer.textFile;
      this.url = fireVoiceLayer.fireTextUrl;
      this.type =  fireVoiceLayerType.TEXT;
    /**
     * this.latitutde = fireVoiceLayer.latitude;
     * this.longitude = fireVoiceLayer.longitude;
     * this.text = fireVoiceLayer.textfile;
     */
  }

  displayText() {
    if (!this.textFile || this.textFile === "") return;

    const position = Cesium.Cartesian3.fromDegrees(this.longitude, this.latitude);
    const textEntity = new Cesium.Entity({
      position: position,
      label: {
        text: this.textFile,
        font: "20px sans-serif",
        fillColor: Cesium.Color.WHITE,
        outlineColor: Cesium.Color.BLACK,
        outlineWidth: 4,
        style: Cesium.LabelStyle.FILL_AND_OUTLINE,
        pixelOffset: new Cesium.Cartesian2(0, -9),
        heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
      }
    });

    uiCesium.viewer.entities.add(textEntity);
  }
}

/**
 * FireVoiceLayer class - parent class for the fire voice layer
 * @type {Map<any, any>}
 */
const fireVoiceDataEntries = new Map(); // unique-key -> Entries; stores Entry objects

var displayEntries = []; // instantiates variable used for temp entry storage
var selectedEntry = undefined; // instantiates variable used for temp entry selection
var selectedType = ["perim", "text"]; // instantiates list of selected layers
//var selectedSat = ["G16", "G18"]; // instantiates list of selected satellites
var followLatest = config.fireVoiceLayer.followLatest; // instantiates followlatest boolean

/**
 * Object for storing entry selections in selection panel
 * @type {{date: undefined, show: boolean, type: string}}
 */
var perimSelection = {
  show: true,
  type: fireVoiceLayerType.PERIM,
  date: undefined,
};

/**
 * Object for storing entry selections in selection panel
 * @type {{date: undefined, show: boolean, type: string}}
 */
var textSelection = {
  show: true,
  type: fireVoiceLayerType.TEXT,
  date: undefined,
};

/**
 * Use the ENUM again to define the entry selections
 * @type {Map<string, {date: undefined, show: boolean, type: string}>}
 */
const selectionEntries = new Map([ // unique-key -> selections; stores selection objects
  [fireVoiceLayerType.PERIM, perimSelection],
  [fireVoiceLayerType.TEXT, textSelection]
]);

// UI initialization
/**
 * Initializes the window
 */
initWindow(); // initializes window
initCheckBoxes(); // initializes checkboxes

var entryView = initEntryView(); // variable storing Entries - modify this to add or remove entries
var selectionView = initSelectionView(); // variable storing selections - modify this to change selections

ws.addWsHandler(handleWsSmokeLayerMessages); // adds handler to websocket

//--- end module initialization

/**
 * Initializes the window for the fire voice layer
 */
function initWindow() {
  createIcon();
  createWindow();
  initContourDisplayControls();
  console.log("ui_cesium_firelayer initialized");
}

/**
 * Creates the icon for the fire voice layer window
 * @returns {*}
 */
function createIcon() {

  console.log("created smoke icon");
  return ui.Icon("smoke-icon.svg", (e)=> ui.toggleWindow(e,'smoke'));
}

/**
 * Creates the window for the fire voice layer
 */
//TODO: change all of the smoke renderings - to what?? - Peter
function initCheckBoxes() { // init checkboxes to their default values
  ui.setCheckBox("smoke.followLatest", followLatest);
  ui.setCheckBox("smoke.G16", selectedSat.includes("G16"));
  ui.setCheckBox("smoke.G17", selectedSat.includes("G17"));
  ui.setCheckBox("smoke.G18", selectedSat.includes("G18"));
}

/**
 * Creates entry view list object
 * @returns {*}
 */
function initEntryView() {
  let view = ui.getList("fireVoiceLayer.entries"); // sets identifier
  if (view) {
    ui.setListItemDisplayColumns(view, ["header"], [
      { name: "sat", tip: "name of satellite", width: "5.5rem", attrs: [], map: e => e.satellite },
      { name: "date", width: "11rem", attrs: ["fixed", "alignLeft"], map: e => util.toLocalMDHMString(e.date)},
    ]);
  }
  return view;
}

/**
 * Creates selection view list object
 * @returns {*}
 */
function initSelectionView() {
  let view = ui.getList("fireVoiceLayer.selection"); // sets identifies
  if (view) {
    ui.setListItemDisplayColumns(view, ["header"], [
      { name: "show", tip: "toggle visibility", width: "2.5rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSource) },
      { name: "type", tip: "type of entry", width: "4rem", attrs: [], map: e => e.type },
      { name: "sat", tip: "name of satellite", width: "3rem", attrs: [], map: e => e.sat },
      { name: "date", width: "7rem", attrs: ["fixed", "alignLeft"], map: e => util.toLocalMDHMString(e.date)},
    ]);
  }
  return view;
}

/**
 * Creates the window
 * @returns {*}
 */
function createWindow() {
  return ui.Window("Smoke and Cloud Layers", "smoke", "smoke-icon.svg")(
      ui.Panel("Data Selection", true)( // data selection panel
          ui.RowContainer()(
              ui.CheckBox("G16", selectSatellite, "smoke.G16"), // name, action on click, tracking id
              ui.CheckBox("G17", selectSatellite, "smoke.G17"),
              ui.CheckBox("G18", selectSatellite, "smoke.G18"),
          ),
          ui.RowContainer()(
              ui.CheckBox("follow latest", toggleFollowLatest, "smoke.followLatest"), // name, action on click, tracking id
              ui.Button("clear", clearSelections) // name, action on click
          ),
          ui.List("fireVoiceLayer.selection", 3) // tracking id, number of visible rows
      ),
      ui.Panel("Data Entries", true)( // data entry panel
          ui.List("fireVoiceLayer.entries", 6, selectSmokeCloudEntry), // tracking id, number of visible rows, action on click
          ui.ListControls("fireVoiceLayer.entries")
      ),
      ui.Panel("Contour Display")( // contour display panel
          ui.Button("reset", resetDisplaySelections),
          ui.Slider("alpha", "smoke.contour.alpha", contourAlphaChanged),
          ui.Slider("stroke width", "smoke.contour.stroke_width", contourStrokeWidthChanged),
          ui.ColorField("stroke color", "smoke.contour.stroke_color", true, contourStrokeColorChanged), // label, id, is input, action on click
          ui.ColorField("smoke color", "smoke.contour.smoke_color", true, contourFillColorChanged),
          ui.ColorField("cloud color", "smoke.contour.cloud_color", true, contourFillColorChanged),
      ),
  );
}

/**
 * Initializes the contour display
 */
function initContourDisplayControls() {

  let e = ui.getSlider("smoke.contour.alpha"); // get slider from id
  ui.setSliderRange(e, 0, 1.0, 0.1); //  set range
  ui.setSliderValue(e, defaultContourRender.alpha); // set value

  let s = ui.getSlider("smoke.contour.stroke_width");
  ui.setSliderRange(s, 0, 3, 0.5);
  ui.setSliderValue(s, defaultContourRender.strokeWidth);

  let sc = ui.getField("smoke.contour.stroke_color"); // get color field from id
  ui.setField(sc, convertColorToStripAlpha(defaultContourRender.strokeColor)); // set color field value

  let colorSmoke = ui.getField("smoke.contour.smoke_color");
  ui.setField(colorSmoke, convertColorToStripAlpha(defaultContourRender.smokeColor));

  let colorCloud = ui.getField("smoke.contour.cloud_color");
  ui.setField(colorCloud, convertColorToStripAlpha(defaultContourRender.cloudColor));
}

/**
 * Initializes the default colors.
 * @param renderConfig
 * @returns {{strokeWidth: (*|number|string), cloudColor: *, alpha: (*|number|AlphaOption|boolean), smokeColor: *, strokeColor: *}}
 */
//--- contour controls
function initDefaultColors(renderConfig) {
  let width = renderConfig.strokeWidth;
  let alpha = renderConfig.alpha;
  let strokeColor = convertColorToHaveAlpha(convertColorToStripAlpha(renderConfig.strokeColor), alpha);
  let smokeColor = convertColorToHaveAlpha(convertColorToStripAlpha(renderConfig.smokeColor), alpha);
  let cloudColor = convertColorToHaveAlpha(convertColorToStripAlpha(renderConfig.cloudColor), alpha);
  let result = {strokeWidth:width, strokeColor:strokeColor, cloudColor:cloudColor, smokeColor:smokeColor, alpha:alpha};//fillColors: fillColors, alpha:alpha};
  return result
}

function contourStrokeWidthChanged(event) {
  let e = ui.getSelectedListItem(entryView);
  if (e) {
    let n = ui.getSliderValue(event.target);
    currentContourRender.strokeWidth = n;
    e.renderChanged();
  }
}

function contourStrokeColorChanged(event) {
  let e = ui.getSelectedListItem(entryView);
  if (e) {
    let clrSpec = event.target.value;
    if (clrSpec) {
      currentContourRender.strokeColor =  convertColorToHaveAlpha(clrSpec, currentContourRender.alpha);
      e.renderChanged();
    }
  }
}

/**
 * Updates the colors of the contours
 * @param event
 */
function contourFillColorChanged(event) {
  let e = ui.getSelectedListItem(entryView);
  if (e) {
    let clrSpec = event.target.value;
    let boxSpec = event.target.id;
    if (clrSpec) { // changes render color according to the fill box changed
      if (boxSpec == "smoke.contour.smoke_color") {
        currentContourRender.smokeColor = convertColorToHaveAlpha(clrSpec, currentContourRender.alpha);
      }
      if (boxSpec == "smoke.contour.cloud_color") {
        currentContourRender.cloudColor = convertColorToHaveAlpha(clrSpec, currentContourRender.alpha);
      }
      e.renderChanged();
    }
  }
  updateColors();
  uiCesium.requestRender();
}

/**
 * Updates the alpha of the contours
 * @param color - the color of the contours
 * @param alpha - the alpha of the contours
 * @returns {*}
 */
function convertColorToHaveAlpha(color, alpha){
  return Cesium.Color.fromAlpha(Cesium.Color.fromCssColorString(color.slice(0,7)), alpha);
}

/**
 * Updates the alpha of the contours
 * @param color - the color of the contours
 * @returns {*}
 */
function convertColorToStripAlpha(color) {
  return color.toCssHexString().slice(0,7)
}

/**
 * Updates the alpha of the contours
 * @param event - the event that is triggered
 */
function updateColors(){ // updates color from user input
  let sColor = currentContourRender.smokeColor; // get color
  let sColorNoAlpha = convertColorToStripAlpha(sColor); // strip current alpha
  currentContourRender.smokeColor =  convertColorToHaveAlpha(sColorNoAlpha, currentContourRender.alpha);
  let cColor = currentContourRender.cloudColor; // get color
  let cColorNoAlpha = convertColorToStripAlpha(cColor); // strip current alpha
  currentContourRender.cloudColor =  convertColorToHaveAlpha(cColorNoAlpha, currentContourRender.alpha);
}

/**
 * Handles the messages from the websocket
 * @param event - the event that is triggered
 */
function contourAlphaChanged(event) {
  let v = ui.getSliderValue(event.target);
  currentContourRender.alpha = v;

  /**
   * Updates the colors with the alpha changes
   */
  updateColors();
  let e = ui.getSelectedListItem(entryView);
  if (e) {
    e.renderChanged();
  }
}

/**
 * resets the display settings to default
 * @param event
 */
function resetDisplaySelections(event) {
  currentContourRender = initDefaultColors(config.fireVoiceLayer.contourRender);
  updateColors();
  initContourDisplayControls();
  selectedEntry = ui.getSelectedListItem(entryView);
  if (selectedEntry) {
    selectedEntry.renderChanged();
  }
}

/**
 * Interactions - behavior for clicking boxes and filtering entries
 * @param event
 */
function clearSelections(event){
  // clear viewed data
  selectedEntry = ui.getSelectedListItem(entryView);
  if (selectedEntry) selectedEntry.clear();
  clearEntries();
  clearSelectionView();
  // clear all checkboxes
  followLatest = false;
  updateEntryView();
  initCheckBoxes();
}

function toggleFollowLatest(event) {
  followLatest = ui.isCheckBoxSelected(event.target);
  if ((followLatest==true) && (ui.getSelectedListItemIndex(entryView) != 0)) {
    ui.selectFirstListItem(entryView);
  }
}

function toggleShowSource(event) { // from data selection checkboxes
  let cb = ui.getCheckBox(event.target)
  let cbName = ui.getListItemOfElement(cb).type;
  if (cbName == fireVoiceLayerType.TEXT) selectCloudEntries(event); // updates according to the checkbox
  if (cbName == fireVoiceLayerType.PERIM) selectSmokeEntries(event); // updates according to the checkbox
  let e = ui.getSelectedListItem(entryView);
  if (e) { // sets selected layers visible and unselected to not visible
    if (selectedType.includes(fireVoiceLayerType.PERIM)) selectedEntry.smokeEntry.SetVisible(true);
    else selectedEntry.smokeEntry.SetVisible(false);
    if (selectedType.includes(fireVoiceLayerType.TEXT)) selectedEntry.cloudEntry.SetVisible(true);
    else selectedEntry.cloudEntry.SetVisible(false);

  }
}
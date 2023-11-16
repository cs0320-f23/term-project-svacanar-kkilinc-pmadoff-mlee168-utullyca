/**
 *  ------------------------------
 *  Description:
 *  ------------------------------
 *  
 *  This script is part of a web-based application designed to visualize and analyze historical wildfire data. 
 *  It fetches data from the "Fire Progression" database available at https://data-nifc.opendata.arcgis.com/ 
 *  and is expected to transition to the "Historical Operational Data" database.
 *  
 *  Main Components:
 *  1. Data Classes and Structures
 *      - FireEntry class for storing fire perimeters and related information.
 *      - Arrays for holding years and fire entries.
 *  
 *  2. Data Fetching and Parsing
 *      - Uses the fetch API to load GeoJSON data for individual fire perimeters.
 *  
 *  3. WebSocket Interface
 *      - Handles real-time messages related to fire history, updating the UI as necessary.
 *  
 *  4. User Interface (UI)
 *      - UI components for selecting years, fires, and visualizing fire data.
 *      - Allows for control over rendering settings such as stroke width, color, etc.
 *  
 *  Cesium Integration:
 *  -------------------
 *  Cesium is used for rendering GeoJSON data on a 3D map. The script makes use of Cesium's GeoJsonDataSource 
 *  class to load and display data, allowing for a more interactive and visually compelling representation 
 *  of fire perimeters.
 *  
 *  Overarching Goal:
 *  ------------------
 *  The primary goal of this script is to provide an intuitive and comprehensive tool for visualizing 
 *  historical fire data. This includes the ability to:
 *      - Browse fire history by year.
 *      - View details of individual fires including their perimeters.
 *      - Dynamically load fire perimeters and render them on a 3D map.
 *      - Provide a UI for controlling various visualization parameters.
 *  
 *  Note: As the database source transitions, the underlying data structure and fetching mechanisms may change.
 *    * Wildfire Data Processor for Frontend
  * 
  * Overview:
  * This script is part of a larger ecosystem designed to deliver wildfire data to a frontend application.
  * It receives raw wildfire data, processes it, and then produces Cesium-compatible objects that can be rendered
  * in a 3D geospatial environment.
  *
  * Input/Output (IO):
  * - Input: JSON messages containing raw wildfire data.
  * - Output: JSON messages containing Cesium-compatible entities (like CZML or GeoJSON). These are sent to a WebSocket.
  *
  * Cesium Objects:
  * This script generates Cesium entities like PointGraphics and BillboardGraphics. Each entity represents a wildfire event
  * with its specific geographic coordinates and additional properties like size and intensity.
  *
  *
  * User Direction:
  * To use this script, ensure that RabbitMQ is up and running and that the Kafka topic to which you want to send 
  * the Cesium entities is properly configured
  * 
  * 
 *  Author: <Mason>
 *  Date: <10/25/23>
 *  
 */

// The script is designed for handling historical fire data from a database and rendering them on a UI.
// It has several dependencies such as config, WebSocket (ws), UI utilities, and several UI component modules.
// The script is majorly divided into different sections: 
// 1. Data Models
// 2. Data manipulation functions
// 3. WebSocket handling
// 4. UI Initialization and interaction.

// --- Data Models Section ---
// The FireEntry class serves as a data model for fire entries.
// It receives fireSummary which is an object representing summary details about a fire.

// --- Data Manipulation Section ---
// Various utility functions like numberOfFiresInYear(), getFireDataItems(), and loadPerimeter() are defined here.
// numberOfFiresInYear(yr) - Takes a year 'yr' as input and returns the number of fires in that year.
// getFireDataItems(e) - Takes a fire entry 'e' and returns its various attributes in a 2D array.
// loadPerimeter(e, perimeter) - Async function for fetching perimeter data and rendering it.

// --- WebSocket Interface ---
// handleWsFireHistoryMessages(msgType, msg) - This function serves as a WebSocket handler for fire history messages.
// It receives messages from a WebSocket (imported as ws) and based on the message type, it triggers different actions.
// In this script, it listens for a "fireSummary" message to handle new fire summary data.

// --- UI Initialization ---
// The UI is initialized using various functions like createIcon(), createWindow(), initFireYearView(), etc.
// These functions set up the UI lists, sliders, checkboxes, and other elements.

// The WebSocket (imported as ws) is used to handle real-time fire history data.
// WebSocket messages are listened to by the handleWsFireHistoryMessages() function and handled accordingly.
// It serves as the central point connecting the real-time data source (WebSocket) to the UI.

// All data flow starts from the WebSocket. Once the data is received, it is processed, stored, and then rendered on the UI.
// Functions like handleFireSummaryMessage() directly interact with the WebSocket data to populate the UI.

// The functions like loadPerimeter() interact with a backend service to fetch geojson data for fire perimeters.
// This is a different data source than the WebSocket but serves to enrich the information available for each fire entry.




/*
 * this is still based on the "Fire Progression" database from https://data-nifc.opendata.arcgis.com/
 * we are moving to the "Historical Operational Data" database (which is structured around polygons/lines/points) the structure will change
 */

import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiData from "./ui_data.js";
import * as uiCesium from "./ui_cesium.js";

//--- data section

class FireEntry {
    constructor (fireSummary) {
        this.fireSummary = fireSummary;
        fireSummary.perimeters.forEach( p=> {
            p.datetime = Date.parse(p.datetime)  // convert string into timestamp
            p.id = util.toYYYYMMDDhhmmZString(p.datetime);
        });
    }

    showPerimeter (perimeter,showIt) {
        let ds = perimeter.ds;
        if (ds) {
            if (ds.show != showIt) {
                ds.show = showIt;
                uiCesium.requestRender();
                ui.updateListItem(firePerimeterView,perimeter);
            }
        } else {
            loadPerimeter( this, perimeter);
        }
    }
}

var years = [];
var fireEntries = [];

function numberOfFiresInYear (yr) {
    var n = 0;
    fireEntries.forEach( e=> {if (e.fireSummary.year == yr) n++;} );
    return n;
}

function getFireDataItems (e) {
    let fs = e.fireSummary;
    return [
        ["name",fs.name],
        ["unique-id", fs.uniqueId],
        ["irwin-id", fs.irwinId],
        ["inciweb-id", fs.inciwebId],
        ["start", fs.start],
        ["contained", fs.contained],
        ["end", fs.end],
        //... more to follow
    ];
}

async function loadPerimeter (e, perimeter) {
    let url = `firehistory-data/${e.fireSummary.year}/${e.fireSummary.name}/perimeters/${perimeter.id}.geojson`;
    
    fetch(url).then( (response) => {
        if (response.ok) {
            let data = response.json();
            if (data) {
                let renderOpts = getPerimeterRenderOptions();
                renderOpts.clampToGround = false; // HACK to make outline visible

                Cesium.GeoJsonDataSource.load(data, renderOpts).then( (ds) => {
                    perimeter.ds = ds;
                    ds.show = true;
                    uiCesium.addDataSource(ds);
                    ui.updateListItem(firePerimeterView,perimeter);

                    console.log("loaded ", url);
                    updatePerimeterRendering(false);
                });
            } else console.log("no data for request: ", url);
        } else console.log("request failed: ", url);
    }, (reason) => console.log("failed to retrieve: ", url, ", reason: ", reason));
}

//--- websocket (data) interface

function handleWsFireHistoryMessages(msgType, msg) {
    switch (msgType) {
        case "fireSummary":
            handleFireSummaryMessage(msg.fireSummary);
            return true;

        default:
            return false;
    }
}

function handleFireSummaryMessage (fireSummary) {
    let e = new FireEntry(fireSummary);
    fireEntries.push( e);
    uiData.sortInUnique(years, fireSummary.year);

    ui.setListItems(fireYearView, years);
}

//--- UI initialization

var fireYearView = undefined;
var selectedYear = undefined;

var fireListView = undefined;
var selectedFire = undefined;

var firePerimeterView = undefined;
var selectedPerimeter = undefined;

var fireHistoryDataView = undefined;

var stepThroughMode = false;
var syncTimelines = false;

var perimeterRender = { ...config.firehistory.perimeterRender };

createIcon();
createWindow();
fireYearView = initFireYearView();
fireListView = initFireListView();
firePerimeterView = initFirePerimeterView();
fireHistoryDataView = ui.getKvTable("firehistory.data");
initFirePerimeterDisplayControls();

ws.addWsHandler(handleWsFireHistoryMessages);

uiCesium.initLayerPanel("firehistory", config.firehistory, showFireHistory);
console.log("ui_cesium_firehistory initialized");

function createIcon() {
    return ui.Icon("firehistory-icon.svg", (e)=> ui.toggleWindow(e,'firehistory'));
}

function createWindow() {
    let view = ui.Window("Historical Fire Data", "firehistory", "firehistory-icon.svg")(
        ui.LayerPanel("firehistory", toggleShowFireHistory),
        ui.Panel("fires", true)(
            ui.RowContainer()(
                ui.List("firehistory.years", 5, selectYear),
                ui.HorizontalSpacer(0.5),
                ui.List("firehistory.fires", 5, selectFire,null,null,zoomToFire)
            )
        ),
        ui.Panel("fire data", true)(
            ui.KvTable("firehistory.data", 15, 25,25)
        ),
        ui.Panel("ignition points", false)(
            ui.List("firehistory.ignitions", 5)
        ),
        ui.Panel("timelines", true) (
            ui.TabbedContainer()(
                ui.Tab("perimeters", true)( ui.List("firehistory.perimeters", 10, selectPerimeter) ),
                ui.Tab("containment", false)( ui.List("firehistory.containment", 10) ),
                ui.Tab("events", false)( ui.List("firehistory.events", 10) ),
                ui.Tab("resources", false)( ui.List("firehistory.resources", 10) ),
                ui.Tab("firelines", false)( ui.List("firehistory.firelines", 10) ),
                ui.Tab("wind", false)( ui.List("firehistory.wind", 10) ),
            ),
            ui.RowContainer()(
                ui.CheckBox("sync timelines", toggleSyncTimelines),
                ui.CheckBox("step through", setStepThrough),
                ui.ListControls("firehistory.perimeters")
            )
        ),
        ui.Panel("display parameters", false)(
            ui.Slider("stroke width", "firehistory.perimeter.stroke_width", perimeterStrokeWidthChanged),
            ui.ColorField("stroke color", "firehistory.perimeter.stroke_color", true, perimeterStrokeColorChanged),
            ui.ColorField("fill color", "firehistory.perimeter.fill_color", true, perimeterFillColorChanged),
            ui.Slider("fill opacity", "firehistory.perimeter.opacity", perimeterFillOpacityChanged),
            ui.Slider("dim factor", "firehistory.perimeter.dim_factor", perimeterDimFactorChanged),
        )
    );

    return view;
}

function initFireYearView() {
    let view = ui.getList("firehistory.years");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "year", tip: "fire season", width: "4rem", attrs: ["fixed"], map: e => e },
            { name: "fires", tip: "number of fires", width: "2rem", attrs: ["fixed", "alignRight"], map: e => numberOfFiresInYear(e) }
        ]);
    }
    return view;
}

function initFireListView() {
    let view = ui.getList("firehistory.fires");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "name", tip: "name of fire", width: "8rem", attrs:[], map: e=> e.fireSummary.name },
            { name: "start", tip: "start date", width: "7rem", attrs:["fixed"], map: e=> e.fireSummary.start },
            { name: "acres", tip: "area in [acre]", width: "4rem", attrs: ["fixed", "alignRight"], map: e => e.fireSummary.acres }
        ]);
    }
    return view;
}

function initFirePerimeterView() {
    let view = ui.getList("firehistory.perimeters");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "show", tip: "show/hide perimeter", width: "2.5rem", attrs: [], map: e => ui.createCheckBox(e.ds && e.ds.show, toggleShowPerimeter) },
            { name: "dtg", tip: "local date/time of perimeter", width: "7rem", attrs:["fixed"], map: e=> util.toLocalMDHMString(e.datetime) },
            { name: "acres", tip: "size in acres", width: "4rem", attrs:["fixed", "alignRight"], map: e=> e.acres },
            ui.listItemSpacerColumn(),
            { name: "agency", tip: "source of perimeter", width: "4rem", attrs: [], map: e => e.agency },
            { name: "method", tip: "method of perimeter determination", width: "7rem", attrs: ["small"], map: e => e.method }
        ]);
    }
    return view;
}

function initFireContainmentView() {
    let view = ui.getList("firehistory.perimeters");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "dtg", tip: "local date/time of perimeter", width: "7rem", attrs:["fixed"], map: e=> util.toLocalMDHMString(e.datetime) },
            { name: "acres", tip: "size in acres", width: "4rem", attrs:["fixed", "alignRight"], map: e=> e.acres },
            { name: "cnt", tip: "% containment", width: "3rem", attrs:["fixed", "alignRight"], map: e=> e.percent },
            { name: "", tip: "", width: "15rem", attrs:[], map: e => ui.createProgressBar(getSizePercent(e.acres), e.percent) }
        ]);
    }
    return view;
}

function getSizePercent(curSize) {
    if (selectFire) {
        let finalArea = selectedFire.fireSummary.acres;
        return curSize * 100 / finalArea;
    } else {
        return 0;
    }
}

function initFirePerimeterDisplayControls() {
    let e = ui.getSlider('firehistory.perimeter.stroke_width');
    ui.setSliderRange(e, 0, 5.0, 0.5, util.f_1);
    ui.setSliderValue(e, perimeterRender.strokeWidth);

    e = ui.getSlider('firehistory.perimeter.opacity');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, perimeterRender.fillOpacity);

    e = ui.getSlider('firehistory.perimeter.dim_factor');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, perimeterRender.dimFactor);

    e = ui.getField("firehistory.perimeter.stroke_color");
    ui.setField(e, perimeterRender.strokeColor.toCssHexString());

    e = ui.getField("firehistory.perimeter.fill_color");
    ui.setField(e, perimeterRender.fillColor.toCssHexString());
}

//--- UI callbacks

function selectYear(event) {
    selectedYear = ui.getSelectedListItem(fireYearView);
    updateFireListView();
    updateFirePerimeterView();
}

function updateFireListView() {
    let fires = fireEntries.filter( e=> e.fireSummary.year == selectedYear );
    fires.sort( (a,b) => { uiData.defaultCompare(a.fireSummary.start, b.fireSummary.start); });
    ui.setListItems(fireListView, fires);
}

function selectFire(event) {
    selectedFire = ui.getSelectedListItem(fireListView);
    updateFireDataView();
    updateFirePerimeterView();
}

function zoomToFire(event) {
    if (selectedFire) {
        let pos = selectedFire.fireSummary.location;
        uiCesium.zoomTo(Cesium.Cartesian3.fromDegrees(pos.lon, pos.lat, config.firehistory.zoomHeight));
    }
}

function updateFirePerimeterView() {
    if (selectedFire) {
        ui.setListItems(firePerimeterView, selectedFire.fireSummary.perimeters);
    } else {
        ui.clearList(firePerimeterView);
    }
}

function selectPerimeter(event) {
    let prevPerimeter = selectedPerimeter;
    selectedPerimeter = ui.getSelectedListItem(firePerimeterView);
    if (stepThroughMode) {
        if (selectedPerimeter) {
            if (prevPerimeter && prevPerimeter.ds) selectedFire.showPerimeter(prevPerimeter,false);
            ui.updateListItem(firePerimeterView,prevPerimeter);

            if (selectedFire) selectedFire.showPerimeter(selectedPerimeter,true);
            ui.updateListItem(firePerimeterView,selectedPerimeter);
        }
    } 
}

function updateFireDataView() {
    let kvList = selectedFire ? getFireDataItems(selectedFire) : null;
    ui.setKvList(fireHistoryDataView, kvList);
}

function toggleShowPerimeter(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let perimeter = ui.getListItemOfElement(cb);
        if (perimeter && selectedFire) {
            selectedFire.showPerimeter(perimeter, ui.isCheckBoxSelected(cb));
        }
    }
}

function setStepThrough(event) {
    stepThroughMode = ui.isCheckBoxSelected(event.target);
}

function toggleSyncTimelines(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        syncTimelines = ui.isCheckBoxSelected(cb);
    }
}

function toggleShowFireHistory(event) {
    let showIt = ui.isCheckBoxSelected(event.target);
    // TBD
}

function showFireHistory(cond) {
    // TBD
}

//--- interactive display parameters

function perimeterStrokeWidthChanged(event) {
    perimeterRender.strokeWidth = ui.getSliderValue(event.target);
    updatePerimeterRendering(false);
}

function perimeterStrokeColorChanged(event) {
    let clrSpec = event.target.value;
    if (clrSpec) {
        perimeterRender.strokeColor = Cesium.Color.fromCssColorString(clrSpec);
        updatePerimeterRendering(false);
    }
}

function perimeterFillOpacityChanged(event) {
    perimeterRender.fillOpacity = ui.getSliderValue(event.target);
    updatePerimeterRendering(false);
}

function perimeterFillColorChanged(event) {
    let clrSpec = event.target.value;
    if (clrSpec) {
        perimeterRender.fillColor = Cesium.Color.fromCssColorString(clrSpec);
        updatePerimeterRendering(false);
    }
}

function perimeterDimFactorChanged(event) {
    perimeterRender.dimFactor = ui.getSliderValue(event.target);
}

function updatePerimeterRendering(onlyPrevious=true) {
    if (selectedFire) {
        let dimFactor = 1.0;
        let skipFirst = onlyPrevious;

        let perimeters = selectedFire.fireSummary.perimeters;
        for (let i=perimeters.length-1; i>=0; i--) { // go in reverse
            let ds = perimeters[i].ds;
            if (ds && ds.show) {
                if (skipFirst) {
                    skipFirst = false;
                } else {
                    let renderOpts = getPerimeterRenderOptions(dimFactor);
                    ds.entities.values.forEach( e=> {
                        if (e.polygon) {
                            e.polygon.material = renderOpts.fill;
                            e.polygon.outlineWidth = renderOpts.strokeWidth;
                            e.polygon.outlineColor = renderOpts.stroke;
                        }
                    });
                }
                dimFactor *= 0.8;
            }
        }

        uiCesium.requestRender();
    }
}

function getPerimeterRenderOptions(dimFactor=1.0) {
    return {
        fill: perimeterRender.fillColor.withAlpha( perimeterRender.fillOpacity * dimFactor),
        stroke: perimeterRender.strokeColor.withAlpha(dimFactor),
        strokeWidth: perimeterRender.strokeWidth
    };
}
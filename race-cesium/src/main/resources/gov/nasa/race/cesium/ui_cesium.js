import * as config from "./config.js";
import * as ui from "./ui.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";

export var viewer = undefined;
export var camera = undefined;

var mapLayerView = undefined;
var selectedMapLayer = undefined;
var activeBaseLayer = undefined;

var imageryLayers = config.imageryLayers;
var imageryParams = {...config.imageryParams }; // we might still adjust them based on theme (hence we have to copy)


ui.registerLoadFunction(function initialize() {
    Cesium.Ion.defaultAccessToken = config.cesiumAccessToken;

    viewer = new Cesium.Viewer('cesiumContainer', {
        terrainProvider: config.terrainProvider,
        skyBox: false,
        infoBox: false,
        baseLayerPicker: false,
        sceneModePicker: false,
        navigationHelpButton: false,
        homeButton: false,
        timeline: false,
        animation: false
    });

    setCanvasSize();
    window.addEventListener('resize', setCanvasSize);

    viewer.resolutionScale = window.devicePixelRatio; // 2.0
    viewer.scene.fxaa = true;

    // event listeners
    viewer.camera.moveEnd.addEventListener(updateCamera);
    viewer.scene.canvas.addEventListener('mousemove', updateMouseLocation);

    ws.addWsHandler(config.wsUrl, handleWsViewMessages);

    adjustImageryParams();
    initImageryLayers();
    initViewWindow();

    ui.registerThemeChangeHandler(themeChanged);

    console.log("ui_cesium initialized");
});

function themeChanged() {
    imageryLayers.forEach(li => li.imageryParams = li.originalImageryParams); // restore original imageryParams
    adjustImageryParams(); // adjust defaults according to theme

    if (selectedMapLayer) { // update selectedMapLayer
        updateSelectedMapLayer();
        setMapSliders(selectedMapLayer);
    }
}

function adjustImageryParams() {
    let docStyle = getComputedStyle(document.documentElement);
    let anyChanged = false;

    imageryParams = {...config.imageryParams }; // reset

    let hue = docStyle.getPropertyValue("--map-hue");
    if (hue) {
        imageryParams.hue = hue;
        anyChanged = true;
    }

    let brightness = docStyle.getPropertyValue("--map-brightness");
    if (brightness) {
        imageryParams.brightness = brightness;
        anyChanged = true;
    }

    let saturation = docStyle.getPropertyValue("--map-saturation");
    if (saturation) {
        imageryParams.saturation = saturation;
        anyChanged = true;
    }

    let contrast = docStyle.getPropertyValue("--map-contrast");
    if (contrast) {
        imageryParams.contrast = contrast;
        anyChanged = true;
    }

    if (anyChanged) updateSelectedMapLayer();
}

function initImageryLayers() {
    let viewerLayers = viewer.imageryLayers;
    let defaultImageryLayer = viewerLayers.get(0); // Cesium uses Bing aerial as default (provider already instantiated)

    imageryLayers.forEach(il => {
        let layer = il.provider ? new Cesium.ImageryLayer(il.provider) : defaultImageryLayer;
        il.layer = layer;

        // save the original imageryParams so that we can later-on restore
        il.originalImageryParams = il.imageryParams;

        let ip = il.imageryParams ? il.imageryParams : config.imageryParams
        setLayerImageryParams(layer, ip);

        if (il.show) {
            if (il.isBase) {
                if (activeBaseLayer) { // there can only be one
                    activeBaseLayer.show = false;
                    activeBaseLayer.layer.show = false;
                }
                activeBaseLayer = il;
            }
            layer.show = true;
            selectedMapLayer = il;
        } else {
            layer.show = false;
        }

        if (il.provider) { // this is a new layer
            viewerLayers.add(layer);
        }
    });
}

function updateSelectedMapLayer() {
    if (selectedMapLayer) {
        if (!selectedMapLayer.imageryParams) { // if we have those set explicitly they take precedence
            setLayerImageryParams(selectedMapLayer.layer, imageryParams);
        }
    }
}

function setLayerImageryParams(layer, imageryParams) {
    layer.alpha = imageryParams.alpha;
    layer.brightness = imageryParams.brightness;
    layer.contrast = imageryParams.contrast;
    layer.hue = imageryParams.hue * Math.PI / 180.0;
    layer.saturation = imageryParams.saturation;
    layer.gamma = imageryParams.gamma;
}

function initViewWindow() {
    mapLayerView = initMapLayerView();
    initMapSliders();
}

function initMapLayerView() {
    let view = ui.getList("view.map.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [{
                name: "show",
                width: "2rem",
                attrs: [],
                map: e => {
                    if (e.isBase) return ui.createRadio(e.show, _toggleShowMapLayer, null);
                    else return ui.createCheckBox(e.show, _toggleShowMapLayer, null);
                }
            },
            { name: "id", width: "12rem", attrs: ["alignLeft"], map: e => e.description }
        ]);

        ui.setListItems(view, config.imageryLayers);
    }

    return view;
}

function _showLayer(layerInfo, newState) {
    layerInfo.show = newState;
    layerInfo.layer.show = newState;
}

// note we get this before the item is selected
function _toggleShowMapLayer(event) {
    let e = ui.getSelector(event.target);
    if (e) {
        let li = ui.getListItemOfElement(e);
        if (li) {
            let show = ui.isSelectorSet(e);
            if (show) {
                if (li.isBase) { // there can only be one at a time
                    if (activeBaseLayer) {
                        let ie = ui.getNthSubElementOfListItem(mapLayerView, activeBaseLayer, 0);
                        if (ie) ui.setSelector(ie.firstChild, false);
                        _showLayer(activeBaseLayer, false);
                    }
                    activeBaseLayer = li;
                }
            }
            _showLayer(li, show)
        }
    }
}

ui.exportToMain(function selectMapLayer(event) {
    let li = ui.getSelectedListItem(mapLayerView);
    if (li) {
        selectedMapLayer = li;
        setMapSliders(li);
    }
})

function getImageryParams(li) {
    if (li.imageryParams) return li.imageryParams; // if we have some set this has precedence
    else return imageryParams;
}

// this sets imageryParams if they are not set
function getModifiableImageryParams(li) {
    if (!li.imageryParams) li.imageryParams = {...imageryParams };
    return li.imageryParams;
}

function setMapSliders(li) {
    let ip = getImageryParams(li);

    ui.setSliderValue('view.map.alpha', ip.alpha);
    ui.setSliderValue('view.map.brightness', ip.brightness);
    ui.setSliderValue('view.map.contrast', ip.contrast);
    ui.setSliderValue('view.map.hue', ip.hue);
    ui.setSliderValue('view.map.saturation', ip.saturation);
    ui.setSliderValue('view.map.gamma', ip.gamma);
}

function initMapSliders() {
    let e = ui.getSlider('view.map.alpha');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.alpha);

    e = ui.getSlider('view.map.brightness');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.brightness);

    e = ui.getSlider('view.map.contrast');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.contrast);

    e = ui.getSlider('view.map.hue');
    ui.setSliderRange(e, 0, 360, 1, util.f_0);
    ui.setSliderValue(e, imageryParams.hue);

    e = ui.getSlider('view.map.saturation');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.saturation);

    e = ui.getSlider('view.map.gamma');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.gamma);
}

function setCanvasSize() {
    viewer.canvas.width = window.innerWidth;
    viewer.canvas.height = window.innerHeight;
}

export function setEntitySelectionHandler(onSelect) {
    let selHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    selHandler.setInputAction(onSelect, Cesium.ScreenSpaceEventType.LEFT_CLICK);
}

export function addDataSource(dataSrc) {
    viewer.dataSources.add(dataSrc);
}

export function clearSelectedEntity() {
    viewer.selectedEntity = null;
}

export function getSelectedEntity() {
    return viewer.selectedEntity;
}

export function setSelectedEntity(e) {
    viewer.selectedEntity = e;
}

//--- generic 'view' window of the UI

function handleWsViewMessages(msgType, msg) {
    switch (msgType) {
        case "camera":
            handleCameraMessage(msg.camera);
            return true;
        case "setClock":
            handleSetClock(msg.setClock);
            return true;
        default:
            return false;
    }
}

//--- websock handler funcs

function handleCameraMessage(newCamera) {
    camera = newCamera;
    setHomeView();
}

function handleSetClock(setClock) {
    ui.setClock("time.utc", setClock.time, setClock.timeScale);
    ui.setClock("time.loc", setClock.time, setClock.timeScale);
    ui.resetTimer("time.elapsed", setClock.timeScale);
    ui.startTime();
}

function updateCamera() {
    let pos = viewer.camera.positionCartographic;
    ui.setField("view.altitude", Math.round(pos.height).toString());
}

function updateMouseLocation(e) {
    var ellipsoid = viewer.scene.globe.ellipsoid;
    var cartesian = viewer.camera.pickEllipsoid(new Cesium.Cartesian3(e.clientX, e.clientY), ellipsoid);
    if (cartesian) {
        let cartographic = ellipsoid.cartesianToCartographic(cartesian);
        let longitudeString = Cesium.Math.toDegrees(cartographic.longitude).toFixed(5);
        let latitudeString = Cesium.Math.toDegrees(cartographic.latitude).toFixed(5);

        ui.setField("view.latitude", latitudeString);
        ui.setField("view.longitude", longitudeString);
    }
}


//--- user control 

export function setHomeView() {
    viewer.selectedEntity = undefined;
    viewer.trackedEntity = undefined;
    viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat, camera.alt),
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
ui.exportToMain(setHomeView);

export function setDownView() {
    viewer.camera.flyTo({
        destination: viewer.camera.positionWC,
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
ui.exportToMain(setDownView);

export function toggleFullScreen(event) {
    ui.toggleFullScreen();
}
ui.exportToMain(toggleFullScreen);

ui.exportToMain(function setImageryProvider(event, providerName) {
    let p = config.imageryProviders.find(p => p.name == providerName);
    if (p) {
        console.log("switching to " + p.name);
        viewer.imageryProvider = p.provider;
    } else {
        console.log("unknown imagery provider: " + providerName);
    }
});

ui.exportToMain(function toggleOverlayProvider(event, providerName) {
    let provider = config.overlayProviders.find(p => p.name == providerName);
    if (provider) {
        console.log("toggle overlay" + providerName);
    } else {
        console.log("unknown overlay provider: " + providerName);
    }
});


//--- explicitly set map rendering parameters for selected layer (will be reset when switching themes)


ui.exportToMain(function setMapAlpha(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.alpha = v;
        getModifiableImageryParams(selectedMapLayer).alpha = v;
    }
});

ui.exportToMain(function setMapBrightness(event) {
    let v = ui.getSliderValue(event.target);
    setMapLayerBrightness(v);
});

function setMapLayerBrightness(v) {
    if (selectedMapLayer) {
        selectedMapLayer.layer.brightness = v;
        getModifiableImageryParams(selectedMapLayer).brightness = v;
    }
}

ui.exportToMain(function setMapContrast(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.contrast = v;
        getModifiableImageryParams(selectedMapLayer).contrast = v;
    }
});

ui.exportToMain(function setMapHue(event) {
    let v = ui.getSliderValue(event.target);
    setMapLayerHue(v);
});

function setMapLayerHue(v) {
    if (selectedMapLayer) {
        selectedMapLayer.layer.hue = (v * Math.PI) / 180; // needs radians
        getModifiableImageryParams(selectedMapLayer).hue = v;
    }
}

ui.exportToMain(function setMapSaturation(event) {
    let v = ui.getSliderValue(event.target);
    setMapLayerSaturation(v);
});

function setMapLayerSaturation(v) {
    if (selectedMapLayer) {
        selectedMapLayer.layer.saturation = v;
        getModifiableImageryParams(selectedMapLayer).saturation = v;
    }
}

ui.exportToMain(function setMapGamma(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.gamma = v;
        getModifiableImageryParams(selectedMapLayer).gamma = v;
    }
});
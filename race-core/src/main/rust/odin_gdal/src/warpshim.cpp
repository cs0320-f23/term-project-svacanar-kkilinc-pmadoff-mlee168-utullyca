#include <gdal_utils.h>

// this is a close copy of the respective function in gdalwarpsimple
// we could do this in Rust but constant unsafe{} and error translation would make this less readable
GDALDatasetH GDALWarpCreateOutput( GDALDatasetH hSrcDS,
                                   const char *pszFilename,
                                   const char *pszFormat,
                                   const char *pszSourceSRS,
                                   const char *pszTargetSRS, int nOrder,
                                   char **papszCreateOptions,
                                   dfMinX: double, dfMaxX: double, dfMinY: double, dfMaxY: double.
                                   dfXRes: double, dfYRes: double,
                                   nForcePixels: int, nForceLines: int) {
    GDALDriverH hDriver;
    GDALDatasetH hDstDS;
    void *hTransformArg;
    double adfDstGeoTransform[6];
    int nPixels = 0, nLines = 0;
    GDALColorTableH hCT;

    hDriver = GDALGetDriverByName(pszFormat);
    if (hDriver == NULL) return NULL;

    hTransformArg = GDALCreateGenImgProjTransformer( hSrcDS, pszSourceSRS, NULL, pszTargetSRS, TRUE, 1000.0, nOrder);
    if (hTransformArg == NULL) return NULL;

    // get approximate output definition
    if (GDALSuggestedWarpOutput(hSrcDS, GDALGenImgProjTransform, hTransformArg,
                                adfDstGeoTransform, &nPixels, &nLines) != CE_None) return NULL;
    GDALDestroyGenImgProjTransformer(hTransformArg);

    //--- did user override parameters?
    if (dfXRes != 0.0 && dfYRes != 0.0) { // given pixel resolutions
        CPLAssert(nPixels == 0 && nLines == 0);
        if (dfMinX == 0.0 && dfMinY == 0.0 && dfMaxX == 0.0 && dfMaxY == 0.0) {
            dfMinX = adfDstGeoTransform[0];
            dfMaxX = adfDstGeoTransform[0] + adfDstGeoTransform[1] * nPixels; // ?? nPixels == 0
            dfMaxY = adfDstGeoTransform[3];
            dfMinY = adfDstGeoTransform[3] + adfDstGeoTransform[5] * nLines;
        }

        nPixels = (int)((dfMaxX - dfMinX + (dfXRes / 2.0)) / dfXRes);
        nLines = (int)((dfMaxY - dfMinY + (dfYRes / 2.0)) / dfYRes);
        adfDstGeoTransform[0] = dfMinX;
        adfDstGeoTransform[3] = dfMaxY;
        adfDstGeoTransform[1] = dfXRes;
        adfDstGeoTransform[5] = -dfYRes;

    } else if (nForcePixels != 0 && nForceLines != 0) {  // given nPixels/nLines
        if (dfMinX == 0.0 && dfMinY == 0.0 && dfMaxX == 0.0 && dfMaxY == 0.0) {
            dfMinX = adfDstGeoTransform[0];
            dfMaxX = adfDstGeoTransform[0] + adfDstGeoTransform[1] * nPixels;
            dfMaxY = adfDstGeoTransform[3];
            dfMinY = adfDstGeoTransform[3] + adfDstGeoTransform[5] * nLines;
        }

        dfXRes = (dfMaxX - dfMinX) / nForcePixels;
        dfYRes = (dfMaxY - dfMinY) / nForceLines;

        adfDstGeoTransform[0] = dfMinX;
        adfDstGeoTransform[3] = dfMaxY;
        adfDstGeoTransform[1] = dfXRes;
        adfDstGeoTransform[5] = -dfYRes;

        nPixels = nForcePixels;
        nLines = nForceLines;

    } else if (dfMinX != 0.0 || dfMinY != 0.0 || dfMaxX != 0.0 || dfMaxY != 0.0) { // given bbox
        dfXRes = adfDstGeoTransform[1];
        dfYRes = fabs(adfDstGeoTransform[5]);

        nPixels = (int)((dfMaxX - dfMinX + (dfXRes / 2.0)) / dfXRes);
        nLines = (int)((dfMaxY - dfMinY + (dfYRes / 2.0)) / dfYRes);

        adfDstGeoTransform[0] = dfMinX;
        adfDstGeoTransform[3] = dfMaxY;
    }

    hDstDS = GDALCreate(hDriver, pszFilename, nPixels, nLines,
                        GDALGetRasterCount(hSrcDS), GDALGetRasterDataType(GDALGetRasterBand(hSrcDS, 1)), papszCreateOptions);

    if (hDstDS != NULL) {
      GDALSetProjection(hDstDS, pszTargetSRS);
      GDALSetGeoTransform(hDstDS, adfDstGeoTransform);

      hCT = GDALGetRasterColorTable(GDALGetRasterBand(hSrcDS, 1));
      if (hCT != NULL) {
          GDALSetRasterColorTable(GDALGetRasterBand(hDstDS, 1), hCT);
      }
    }

    return hDstDS;
}
/*
 *      Copyright (c) 2004-2013 YAMJ Members
 *      http://code.google.com/p/moviejukebox/people/list
 *
 *      This file is part of the Yet Another Movie Jukebox (YAMJ).
 *
 *      The YAMJ is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU General Public License as published by
 *      the Free Software Foundation, either version 3 of the License, or
 *      any later version.
 *
 *      YAMJ is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License
 *      along with the YAMJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 *      Web: http://code.google.com/p/moviejukebox/
 *
 */
package com.moviejukebox.scanner.artwork;

import com.moviejukebox.model.Artwork.ArtworkType;
import com.moviejukebox.model.DirtyFlag;
import com.moviejukebox.model.IImage;
import com.moviejukebox.model.Jukebox;
import com.moviejukebox.model.Movie;
import com.moviejukebox.plugin.DefaultBackgroundPlugin;
import com.moviejukebox.plugin.DefaultImagePlugin;
import com.moviejukebox.plugin.MovieImagePlugin;
import com.moviejukebox.tools.*;
import static com.moviejukebox.tools.PropertiesUtil.FALSE;
import static com.moviejukebox.tools.PropertiesUtil.TRUE;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

/**
 * Scanner for artwork
 *
 * @author Stuart
 */
public abstract class ArtworkScanner implements IArtworkScanner {

    //These are the properties used:
    //  {artworkType}.scanner.artworkSearchLocal - true/false
    //  {artworkType}.scanner.artworkExtensions - The extensions to search for the artwork
    //  {artworkType}.scanner.artworkToken - A token that delimits the artwork, such as ".fanart" or ".banner"
    //  {artworkType}.scanner.imageName - List of fixed artwork names
    //  {artworkType}.scanner.Validate -
    //  {artworkType}.scanner.ValidateMatch -
    //  {artworkType}.scanner.ValidateAspect -
    //  {artworkType}.scanner.artworkDirectory -
    //  {artworkType}.scanner.artworkPriority - mjb.skin.dir - Skin directory
    //
    // From skin.properties
    //  ???.width
    //  ???.height
    private static final Logger logger = Logger.getLogger(ArtworkScanner.class);
    protected String LOG_MESSAGE;                        // The start of the log message
    private static final String SPLITTER = ",;|";
    protected final WebBrowser webBrowser = new WebBrowser();
    protected MovieImagePlugin artworkImagePlugin;
    protected String skinHome;                          // Location of the skin files used to get the dummy images from for missing artwork
    // Scanner settings
    protected ArtworkType artworkType;                  // The type of the artwork. Will be used to load the other properties. When using for properties, must be lowercase
    protected String artworkTypeName;                   // The artwork type name to use in properties (all lowercase)
    protected boolean artworkOverwrite = Boolean.FALSE;
    // Search settings
    protected boolean artworkSearchLocal;               // Should we search for local artwork
    protected boolean artworkDownloadMovie;             // Whether or not to download artwork for a Movie
    protected boolean artworkDownloadTv;                // Whether or not to download artwork for a TV Show
    // Local search settings
    protected Collection<String> artworkExtensions = new ArrayList<String>();
    protected Collection<String> artworkImageName = new ArrayList<String>();    // List of fixed artwork names
    private String artworkDirectory;                    // The name of the artwork directory to search from from either the video directory or the root of the library
    private String artworkPriority;                     // The order of the searches performed for the local artwork.
    // Artwork attributes
    protected String artworkFormat;                     // Format of the artwork to save, e.g. JPG, PNG, etc.
    protected String artworkToken;                      // The suffix of the artwork filename to search for.
    protected int artworkWidth;                         // The width of the image from the skin.properties for use in the validation routine
    protected int artworkHeight;                        // The height of the image from the skin.properties for use in the validation routine
    // Artwork validation
    protected boolean artworkValidate;                  // Should the artwork be validated or not.
    protected boolean artworkValidateAspect;            // Should the artwork be validated for it's aspect
    protected int artworkValidateMatch;                 // How close the image should be to the expected dimensions (artworkWidth & artworkHeight)

    /**
     * Construct the
     *
     * @param conArtworkType
     */
    public ArtworkScanner(ArtworkType conArtworkType) {
        setArtworkType(conArtworkType);

        StringTokenizer st = new StringTokenizer(PropertiesUtil.getProperty(conArtworkType + ".scanner.imageName", ""), SPLITTER);
        while (st.hasMoreTokens()) {
            artworkImageName.add(st.nextToken());
        }

        // Get artwork scanner behaviour
        artworkSearchLocal = PropertiesUtil.getBooleanProperty(artworkTypeName + ".scanner.searchForExistingArtwork", FALSE);
        artworkDownloadMovie = PropertiesUtil.getBooleanProperty(artworkTypeName + ".movie.download", FALSE);
        artworkDownloadTv = PropertiesUtil.getBooleanProperty(artworkTypeName + ".tv.download", FALSE);

        setArtworkExtensions(PropertiesUtil.getProperty(artworkTypeName + ".scanner.artworkExtensions", "jpg,png,gif"));
        artworkToken = PropertiesUtil.getProperty(artworkTypeName + ".scanner.artworkToken", "");
        if (StringUtils.isBlank(artworkToken) && artworkType != ArtworkType.Poster) {
            // If the token is empty, create a default from the name
            artworkToken = "." + artworkTypeName;
        }

        artworkFormat = PropertiesUtil.getProperty(artworkTypeName + ".format", "jpg");
        setArtworkImageName(PropertiesUtil.getProperty(artworkTypeName + ".scanner.imageName", ""));

        artworkValidate = PropertiesUtil.getBooleanProperty(artworkTypeName + ".scanner.Validate", TRUE);
        artworkValidateMatch = PropertiesUtil.getIntProperty(artworkTypeName + ".scanner.ValidateMatch", "75");
        artworkValidateAspect = PropertiesUtil.getBooleanProperty(artworkTypeName + ".scanner.ValidateAspect", TRUE);

        artworkDirectory = PropertiesUtil.getProperty(artworkTypeName + ".scanner.artworkDirectory", "");

        // Get the priority (order) that the artwork is searched for
        artworkPriority = PropertiesUtil.getProperty(artworkTypeName + ".scanner.artworkPriority", "video,folder,fixed,series,directory");

        // Get & set the default artwork dimensions
        setArtworkDimensions();

        // Set the image plugin
        setArtworkImagePlugin();

        skinHome = PropertiesUtil.getProperty("mjb.skin.dir", "./skins/default");
    }

    /**
     * Save the artwork to the jukebox
     *
     * TODO: Parameter to control if the original artwork is saved in the jukebox or not. We should save this in an
     * "originalArtwork" folder or something
     *
     * @return the status of the save. True if saved correctly, false otherwise.
     */
    @Override
    public boolean saveArtworkToJukebox(Jukebox jukebox, Movie movie) {

        String artworkUrl = getArtworkUrl(movie);
        String artworkFilename = getArtworkFilename(movie);

        if (!StringTools.isValidString(artworkUrl)) {
//            logger.debug(LOG_MESSAGE + "Invalid " + artworkType + " URL - " + artworkUrl); // XXX DEBUG
            return Boolean.FALSE;
        }

        if (!StringTools.isValidString(artworkFilename)) {
//            logger.debug(LOG_MESSAGE + "Invalid " + artworkType + " filename - " + artworkFilename); // XXX DEBUG
            return Boolean.FALSE;
        }

        String artworkPath = StringTools.appendToPath(jukebox.getJukeboxRootLocationDetails(), artworkFilename);
        if (!artworkOverwrite && FileTools.fileCache.fileExists(artworkPath)) {
            logger.debug(LOG_MESSAGE + "Artwork exists for " + movie.getBaseName());
            return Boolean.TRUE;
        }
        artworkPath = StringTools.appendToPath(jukebox.getJukeboxTempLocationDetails(), artworkFilename);

        File artworkFile = FileTools.fileCache.getFile(artworkPath);

        if (artworkUrl.startsWith("http")) {
            // Looks like a URL so download it
            logger.debug(LOG_MESSAGE + "Saving " + artworkType + " for " + movie.getBaseName() + " to " + artworkPath);
            try {
                // Download the artwork using the proxy save downloadImage
                FileTools.downloadImage(artworkFile, artworkUrl);
                BufferedImage artworkImage = GraphicTools.loadJPEGImage(artworkFile);

                String pluginType = getPropertyName();

                if (artworkImage != null) {
                    artworkImage = artworkImagePlugin.generate(movie, artworkImage, pluginType, null);
                    GraphicTools.saveImageToDisk(artworkImage, artworkPath);
                } else {
                    setArtworkFilename(movie, Movie.UNKNOWN);
                    setArtworkUrl(movie, Movie.UNKNOWN);
                    return Boolean.FALSE;
                }
            } catch (MalformedURLException error) {
                logger.debug(LOG_MESSAGE + "Failed to download " + artworkType + ": " + artworkUrl + " doesn't look like a proper URL");
                return Boolean.FALSE;
            } catch (Exception error) {
                logger.debug(LOG_MESSAGE + "Failed to download " + artworkType + ": " + artworkUrl);
                logger.error(SystemTools.getStackTrace(error));
                return Boolean.FALSE;
            }
        } else {
            // Looks like a file, so copy it
            logger.debug(LOG_MESSAGE + "Saving " + artworkType + " for " + movie.getBaseName() + " to " + artworkPath);
            try {
                FileTools.copyFile(artworkUrl, artworkPath);
            } catch (Exception error) {
                logger.error(LOG_MESSAGE + "Failed to copy " + artworkType + ": " + artworkUrl);
                logger.error(SystemTools.getStackTrace(error));
            }
        }

        return Boolean.TRUE;
    }

    /**
     * Get the artwork filename from the movie object based on the artwork type
     *
     * @param movie
     * @return the Artwork Filename
     */
    @Override
    public abstract String getArtworkFilename(Movie movie);

    /**
     * Get the artwork URL from the movie object based on the artwork type
     *
     * @param movie
     * @return the Artwork URL
     */
    @Override
    public abstract String getArtworkUrl(Movie movie);

    @Override
    public abstract boolean isDirtyArtwork(Movie movie);

    @Override
    public final boolean isSearchRequired() {
        // Assume the artwork is required
        return isSearchLocal() || isSearchOnline();
    }

    @Override
    public final boolean isSearchLocal() {
        return artworkSearchLocal;
    }

    @Override
    public final boolean isSearchOnline() {
        return artworkDownloadTv || artworkDownloadMovie;
    }

    @Override
    public final boolean isSearchOnline(Movie movie) {
        if (!movie.isScrapeLibrary()) {
            return Boolean.FALSE;
        }

        if (!isSearchOnline()) {
            return Boolean.FALSE;
        }

        if (movie.isTVShow() && !artworkDownloadTv) {
            return Boolean.FALSE;
        } else if (!movie.isTVShow() && !artworkDownloadMovie) {
            return Boolean.FALSE;
        }

        for (Entry<String, String> e : movie.getIdMap().entrySet()) {
            if (("0".equals(e.getValue())) || ("-1".equals(e.getValue()))) {
                // Stop and return
                return Boolean.FALSE;
            }
        }

        return Boolean.TRUE;
    }

    public static boolean isRequired(ArtworkType artworkType) {
        return isRequired(artworkType.toString().toLowerCase());
    }

    /**
     * Determine if the artwork type is required or not
     *
     * @param artworkTypeString
     * @return
     */
    public static boolean isRequired(String artworkTypeString) {
        boolean sArtworkLocalSearch = PropertiesUtil.getBooleanProperty(artworkTypeString + ".scanner.searchForExistingArtwork", FALSE);
        boolean sArtworkMovieDownload = PropertiesUtil.getBooleanProperty(artworkTypeString + ".movie.download", FALSE);
        boolean sArtworkTvDownload = PropertiesUtil.getBooleanProperty(artworkTypeString + ".tv.download", FALSE);

        return (sArtworkLocalSearch || sArtworkMovieDownload || sArtworkTvDownload);
    }

    public static EnumSet<ArtworkType> getRequiredArtworkTypes() {
        EnumSet<ArtworkType> artworkTypeRequired = EnumSet.noneOf(ArtworkType.class);
        for (ArtworkType artworkType : EnumSet.allOf(ArtworkType.class)) {
            if (isRequired(artworkType)) {
                artworkTypeRequired.add(artworkType);
            }
        }
        return artworkTypeRequired;
    }

    /**
     * A catch all routine to scan local artwork and then online artwork.
     */
    @Override
    public final String scan(Jukebox jukebox, Movie movie) {
        /*
         * We need to check some things before we start scanning
         *
         * 1) If the artwork exists, do we need to overwrite it? (force
         * overwrite?)
         *
         * 2) Force overwrite should NOT check the jukebox for artwork.
         */

        // If we are not required, leave
        if (!isSearchRequired()) {
//            logger.info(LOG_MESSAGE + movie.getBaseFilename() + " " + artworkTypeName + " not required");    // XXX DEBUG
            return getArtworkUrl(movie);
        }

        // If forceOverwrite is set, clear the Url so we will search again
        if (isOverwrite()) {
            logger.debug(LOG_MESSAGE + "forceOverwite set, clearing URL before search"); // XXX DEBUG
            setArtworkUrl(movie, Movie.UNKNOWN);
            setArtworkFilename(movie, Movie.UNKNOWN);
        } else {
            // Check to see if we have a valid URL and it's not dirty
            if (StringTools.isValidString(getArtworkUrl(movie)) && !(isDirtyArtwork(movie) || movie.isDirty(DirtyFlag.INFO))) {
                // Valid URL, so exit with that
                logger.debug(LOG_MESSAGE + "URL for " + movie.getBaseName() + " looks valid, skipping online search: " + getArtworkUrl(movie));
                if (StringTools.isNotValidString(getArtworkFilename(movie))) {
                    setArtworkFilename(movie, makeSafeArtworkFilename(movie));
                }
                return getArtworkUrl(movie);
            } else {
                // Not a valid URL, check to see if the artwork is dirty or the movie is dirty
                if (!(isDirtyArtwork(movie) || movie.isDirty(DirtyFlag.INFO) || movie.isDirty(DirtyFlag.RECHECK))) {
                    // Artwork and movie is not dirty, so don't process
                    logger.debug(LOG_MESSAGE + "URL update not required (not overwrite, dirty or recheck)");
                    return getArtworkUrl(movie);
                }
            }
        }

        String artworkUrl;
        if (isSearchLocal()) {
            artworkUrl = scanLocalArtwork(jukebox, movie);
            logger.debug(LOG_MESSAGE + "ScanLocalArtwork returned: " + artworkUrl); // XXX DEBUG
            if (StringTools.isValidString(artworkUrl)) {
//            logger.debug(LOG_MESSAGE + "ArtworkUrl found, so CopyLocalFile triggered"); // XXX DEBUG

                // Update the movie artwork URL
                setArtworkUrl(movie, artworkUrl);

                // Only set the filename if we have an artwork URL
                setArtworkFilename(movie, makeSafeArtworkFilename(movie));

                // Save the artwork to the jukebox
                copyLocalFile(jukebox, movie);
                return artworkUrl;
            }
        } else {
            artworkUrl = Movie.UNKNOWN;
        }

        if (StringTools.isNotValidString(artworkUrl) && isSearchOnline(movie)) {
            logger.debug(LOG_MESSAGE + "Scanning for online artwork for " + movie.getBaseName()); // XXX DEBUG
            artworkUrl = scanOnlineArtwork(movie);

            if (StringTools.isValidString(artworkUrl)) {
                // Update the movie artwork URL
                setArtworkUrl(movie, artworkUrl);

                // Only set the filename if we have an artwork URL
                setArtworkFilename(movie, makeSafeArtworkFilename(movie));

                // Save the artwork to the jukebox
                saveArtworkToJukebox(jukebox, movie);
            } else {
                logger.debug(LOG_MESSAGE + "No online artwork found for " + movie.getBaseName());
            }
        }

        return artworkUrl;
    }

    /**
     * Scan for any local artwork and return the path to it.
     *
     * This should only be called by the scanLocalArtwork method in the derived classes.
     *
     * Note: This will update the movie information for this artwork
     *
     * @param jukebox
     * @param movie
     * @return The URL (path) to the first found artwork in the priority list
     */
    protected String scanLocalArtwork(Jukebox jukebox, Movie movie, MovieImagePlugin artworkImagePlugin) {
        // Check to see if we are required
        if (!isSearchLocal()) {
            // return the current URL
            return getArtworkUrl(movie);
        }

        String movieArtwork = Movie.UNKNOWN;
        String artworkFilename = movie.getBaseFilename() + artworkToken;

        logger.debug(LOG_MESSAGE + "Searching for '" + artworkFilename + "'");

        StringTokenizer st = new StringTokenizer(artworkPriority, SPLITTER);

        while (st.hasMoreTokens() && movieArtwork.equalsIgnoreCase(Movie.UNKNOWN)) {
            String artworkSearch = st.nextToken().toLowerCase().trim();

            if (artworkSearch.equals("video")) {
                movieArtwork = scanVideoArtwork(movie, artworkFilename);
//                logger.debug(LOG_MESSAGE + movie.getBaseFilename() + " scanVideoArtwork    : " + movieArtwork); // XXX DEBUG
                continue;
            }

            if (artworkSearch.equals("folder")) {
                movieArtwork = scanFolderArtwork(movie);
//                logger.debug(LOG_MESSAGE + movie.getBaseFilename() + " scanFolderArtwork   : " + movieArtwork); // XXX DEBUG
                continue;
            }

            if (artworkSearch.equals("fixed")) {
                movieArtwork = scanFixedArtwork(movie);
//                logger.debug(LOG_MESSAGE + movie.getBaseFilename() + " scanFixedArtwork    : " + movieArtwork); // XXX DEBUG
                continue;
            }

            if (artworkSearch.equals("series")) {
                // This is only for TV Sets as it searches the directory above the one the episode is in for fixed artwork
                if (movie.isTVShow() && movie.isSetMaster()) {
                    movieArtwork = scanTvSeriesArtwork(movie);
//                    logger.debug(LOG_MESSAGE + movie.getBaseFilename() + " scanTvSeriesArtwork : " + movieArtwork); // XXX DEBUG
                }
                continue;
            }

            if (artworkSearch.equals("directory")) {
                movieArtwork = scanArtworkDirectory(movie, artworkFilename);
//                logger.debug(LOG_MESSAGE + movie.getBaseFilename() + " scanArtworkDirectory: " + movieArtwork); // XXX DEBUG
                continue;
            }

        }

        if (StringTools.isValidString(movieArtwork)) {
            logger.debug(LOG_MESSAGE + "Found artwork for " + movie.getBaseName() + ": " + movieArtwork);
        } else {
            logger.debug(LOG_MESSAGE + "No local artwork found for " + movie.getBaseName());
        }
        setArtworkUrl(movie, movieArtwork);

        return movieArtwork;
    }

    @Override
    public abstract void setArtworkFilename(Movie movie, String artworkFilename);

    @Override
    public abstract void setArtworkUrl(Movie movie, String artworkUrl);

    /**
     * Updates the artwork by either copying the local file or downloading the artwork
     *
     * @param jukebox
     * @param movie
     */
    @Deprecated
    public void updateArtwork(Jukebox jukebox, Movie movie) {
        String artworkFilename = getArtworkFilename(movie);
        String artworkUrl = getArtworkUrl(movie);
        String artworkDummy;

        if (artworkType == ArtworkType.Poster) {
            artworkDummy = "dummy.jpg";
        } else if (artworkType == ArtworkType.Fanart) {
            // There is no dummy artwork for fanart
            artworkDummy = "";
        } else if (artworkType == ArtworkType.Banner) {
            artworkDummy = "dummy_banner.jpg";
        } else if (artworkType == ArtworkType.VideoImage) {
            artworkDummy = "dummy_videoimage.jpg";
        } else {
            // guess a default dummy name
            artworkDummy = "dummy_" + artworkTypeName + "." + artworkFormat;
            logger.debug((LOG_MESSAGE + "Using dummy image name of '" + artworkDummy + "'"));
        }

        File artworkFile = FileTools.fileCache.getFile(StringTools.appendToPath(jukebox.getJukeboxRootLocationDetails(), artworkFilename));
        File tmpDestFile = new File(StringTools.appendToPath(jukebox.getJukeboxTempLocationDetails(), artworkFilename));

        // Do not overwrite existing artwork, unless there is a new URL in the nfo file.
        if ((!tmpDestFile.exists() && !artworkFile.exists()) || isDirtyArtwork(movie) || isOverwrite()) {
            artworkFile.getParentFile().mkdirs();

            if (artworkUrl == null || artworkUrl.equals(Movie.UNKNOWN)) {
                logger.debug("Dummy " + artworkType + " used for " + movie.getBaseName());
                FileTools.copyFile(new File(skinHome + File.separator + "resources" + File.separator + artworkDummy), tmpDestFile);
            } else {
                if (StringTools.isValidString(artworkDummy)) {
                    try {
                        logger.debug("Saving " + artworkType + " for " + movie.getBaseName() + " to " + tmpDestFile.getName());
                        FileTools.downloadImage(tmpDestFile, artworkUrl);
                    } catch (IOException ex) {
                        logger.debug("Failed downloading " + artworkType + ": " + artworkUrl + " - " + ex.getMessage());
                        FileTools.copyFile(new File(skinHome + File.separator + "resources" + File.separator + artworkDummy), tmpDestFile);
                    }
                } else {
                    logger.debug(LOG_MESSAGE + "No dummy artwork ('" + artworkDummy + "') for " + artworkType);
                }
            }
        } else {
            saveArtworkToJukebox(jukebox, movie);
        }
    }

    @Override
    public boolean validateArtwork(IImage artworkImage) {
        return validateArtwork(artworkImage, artworkWidth, artworkHeight, artworkValidateAspect);
    }

    /**
     * Get the size of the file at the end of the URL
     *
     * Taken from: http://forums.sun.com/thread.jspa?threadID=528155&messageID=2537096
     *
     * @param posterImage Artwork image to check
     * @param posterWidth The width to check
     * @param posterHeight The height to check
     * @param checkAspect Should the aspect ratio be checked
     * @return True if the poster is good, false otherwise
     */
    @Override
    public boolean validateArtwork(IImage artworkImage, int artworkWidth, int artworkHeight, boolean checkAspect) {
        @SuppressWarnings("rawtypes")
        Iterator readers = ImageIO.getImageReadersBySuffix("jpeg");
        ImageReader reader = (ImageReader) readers.next();
        int urlWidth;
        int urlHeight;
        float urlAspect;

        if (!artworkValidate) {
            return Boolean.TRUE;
        }

        if (artworkImage.getUrl().equalsIgnoreCase(Movie.UNKNOWN)) {
            return Boolean.FALSE;
        }

        try {
            URL url = new URL(artworkImage.getUrl());
            InputStream in = url.openStream();
            ImageInputStream iis = ImageIO.createImageInputStream(in);
            reader.setInput(iis, Boolean.TRUE);
            urlWidth = reader.getWidth(0);
            urlHeight = reader.getHeight(0);
        } catch (IOException ignore) {
            logger.debug(LOG_MESSAGE + "ValidateArtwork error: " + ignore.getMessage() + ": can't open URL");
            return Boolean.FALSE; // Quit and return a Boolean.FALSE poster
        }

        urlAspect = (float) urlWidth / (float) urlHeight;

        if (checkAspect && urlAspect > 1.0) {
            logger.debug(LOG_MESSAGE + artworkImage + " rejected: URL is wrong aspect (portrait/landscape)");
            return Boolean.FALSE;
        }

        // Adjust artwork width / height by the ValidateMatch figure
        int newArtworkWidth = artworkWidth * (artworkValidateMatch / 100);
        int newArtworkHeight = artworkHeight * (artworkValidateMatch / 100);

        if (urlWidth < newArtworkWidth) {
            logger.debug(LOG_MESSAGE + artworkImage + " rejected: URL width (" + urlWidth + ") is smaller than artwork width (" + newArtworkWidth + ")");
            return Boolean.FALSE;
        }

        if (urlHeight < newArtworkHeight) {
            logger.debug(LOG_MESSAGE + artworkImage + " rejected: URL height (" + urlHeight + ") is smaller than artwork height (" + newArtworkHeight + ")");
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * Copy the local artwork file to the jukebox.
     *
     * If there's no URL do not create dummy artwork
     *
     * @param jukebox
     * @param movie
     * @return
     */
    protected String copyLocalFile(Jukebox jukebox, Movie movie) {
        String fullArtworkFilename = getArtworkUrl(movie);

        if (fullArtworkFilename.equalsIgnoreCase(Movie.UNKNOWN)) {
            logger.debug(LOG_MESSAGE + "No local " + artworkType + " found for " + movie.getBaseName());
            return Movie.UNKNOWN;
        } else {
            logger.debug(LOG_MESSAGE + fullArtworkFilename + " found");
            if (overwriteArtwork(jukebox, movie)) {
                String destFilename = getArtworkFilename(movie);
                String destFullPath = StringTools.appendToPath(jukebox.getJukeboxTempLocationDetails(), destFilename);

                FileTools.copyFile(fullArtworkFilename, destFullPath);
                logger.debug(LOG_MESSAGE + fullArtworkFilename + " has been copied to " + destFilename);
            } else {
                // Don't copy the file
                logger.debug(LOG_MESSAGE + fullArtworkFilename + " does not need to be copied");
            }

            return fullArtworkFilename;
        }
    }

    /**
     * returns the forceOverwite parameter for this artwork type.
     *
     * This is set in the constructor of each of the sub-scanners
     *
     * @return
     */
    protected boolean isOverwrite() {
        return artworkOverwrite;
    }

    /**
     * Determine if the artwork should be overwritten
     *
     * Checks to see if the artwork exists in the jukebox folders (temp and final)
     *
     * Checks the overwrite parameters Checks to see if the local artwork is newer
     *
     * @param movie
     * @return
     */
    protected boolean overwriteArtwork(Jukebox jukebox, Movie movie) {
        if (isOverwrite()) {
            setDirtyArtwork(movie, Boolean.TRUE);
//            logger.debug(LOG_MESSAGE + "Artwork overwrite"); // XXX DEBUG
            return Boolean.TRUE;
        }

        if (isDirtyArtwork(movie)) {
//            logger.debug(LOG_MESSAGE + "Dirty artwork"); // XXX DEBUG
            return Boolean.TRUE;
        }

        // This is the filename & path of the artwork that was found
        String artworkFilename = getArtworkFilename(movie);
        File artworkFile = new File(artworkFilename);

        // This is the filename & path of the jukebox artwork
        String jukeboxFilename = StringTools.appendToPath(jukebox.getJukeboxRootLocationDetails(), artworkFilename);
        File jukeboxFile = FileTools.fileCache.getFile(jukeboxFilename);

        // This is the filename & path for temporary storage jukebox
        String tempLocFilename = StringTools.appendToPath(jukebox.getJukeboxTempLocationDetails(), artworkFilename);
        File tempLocFile = new File(tempLocFilename);

        // Check to see if the found artwork newer than the temp jukebox
        if (FileTools.fileCache.fileExists(tempLocFile)) {
            if (FileTools.isNewer(artworkFile, tempLocFile)) {
                setDirtyArtwork(movie, Boolean.TRUE);
                logger.debug(LOG_MESSAGE + movie.getBaseName() + ": Local artwork is newer, overwritting existing artwork"); // XXX DEBUG
                return Boolean.TRUE;
            }
        } else if (FileTools.fileCache.fileExists(jukeboxFile)) {
            // Check to see if the found artwork newer than the existing jukebox
            if (FileTools.isNewer(artworkFile, jukeboxFile)) {
                setDirtyArtwork(movie, Boolean.TRUE);
                logger.debug(LOG_MESSAGE + movie.getBaseName() + ": Local artwork is newer, overwritting existing jukebox artwork"); // XXX DEBUG
                return Boolean.TRUE;
            } else {
                logger.debug(LOG_MESSAGE + "Local artwork is older, not copying"); // XXX DEBUG
                return Boolean.FALSE;
            }
        } else {
            logger.debug(LOG_MESSAGE + "No jukebox file found, file will be copied"); // XXX DEBUG
            return Boolean.TRUE;
        }

        // TODO: Any other checks that can be made for the artwork?

        // All the tests passed so don't overwrite
        return Boolean.FALSE;
    }

    /**
     * Scan an absolute or relative path for the movie images.
     *
     * The relative path should include the directory of the movie as well as the library root
     *
     * @param movie
     * @return UNKNOWN or Absolute Path
     */
    protected String scanArtworkDirectory(Movie movie, String artworkFilename) {
        String artworkPath = Movie.UNKNOWN;

        if (!artworkDirectory.equals("")) {
            String artworkLibraryPath = StringTools.appendToPath(movie.getLibraryPath(), artworkDirectory);

            artworkPath = scanVideoArtwork(movie, artworkFilename, artworkDirectory);

            if (artworkPath.equalsIgnoreCase(Movie.UNKNOWN)) {
                artworkPath = scanDirectoryForArtwork(artworkFilename, artworkLibraryPath);
            }
        }
        return artworkPath;
    }

    /**
     * Scan the passed directory for the artwork filename
     *
     * @param artworkFilename
     * @param artworkPath
     * @return UNKNOWN or Absolute Path
     */
    protected String scanDirectoryForArtwork(String artworkFilename, String artworkPath) {
        String fullFilename = StringTools.appendToPath(artworkPath, artworkFilename);
        File artworkFile = FileTools.findFileFromExtensions(fullFilename, artworkExtensions);

        if (artworkFile.exists()) {
            return artworkFile.getAbsolutePath();
        }
        return Movie.UNKNOWN;
    }

    /**
     * Scan for fixed name artwork, such as folder.jpg, backdrop.png, etc.
     *
     * @param movie
     * @return UNKNOWN or Absolute Path
     */
    protected String scanFixedArtwork(Movie movie) {
        String parentPath = FileTools.getParentFolder(movie.getFile());
        String fullFilename;
        File artworkFile;

        for (String imageFileName : artworkImageName) {
            fullFilename = StringTools.appendToPath(parentPath, imageFileName);
            artworkFile = FileTools.findFileFromExtensions(fullFilename, artworkExtensions);

            if (artworkFile.exists()) {
                return artworkFile.getAbsolutePath();
            }
        }
        return Movie.UNKNOWN;
    }

    /**
     * Scan artwork that is named the same as the folder that it is in
     *
     * @param movie
     * @return UNKNOWN or Absolute Path
     */
    protected String scanFolderArtwork(Movie movie) {
        String parentPath = FileTools.getParentFolder(movie.getFile());
        String fullFilename = StringTools.appendToPath(parentPath, FileTools.getParentFolderName(movie.getFile()) + artworkToken);

        File artworkFile = FileTools.findFileFromExtensions(fullFilename, artworkExtensions);

        if (artworkFile.exists()) {
            return artworkFile.getAbsolutePath();
        }

        return Movie.UNKNOWN;
    }

    /**
     * Scan for TV show SERIES artwork.
     *
     * This usually exists in the directory ABOVE the one the files reside in
     *
     * @param movie
     * @return UNKNOWN or Absolute Path
     */
    protected String scanTvSeriesArtwork(Movie movie) {

        if (!movie.isTVShow()) {
            // Don't process if we are a movie.
            return Movie.UNKNOWN;
        }

        String parentPath = FileTools.getParentFolder(movie.getFile().getParentFile().getParentFile());
        String fullFilename;
        File artworkFile;

        for (String imageFileName : artworkImageName) {
            fullFilename = StringTools.appendToPath(parentPath, imageFileName);
            artworkFile = FileTools.findFileFromExtensions(fullFilename, artworkExtensions);
            if (artworkFile.exists()) {
                return artworkFile.getAbsolutePath();
            }
        }

        return Movie.UNKNOWN;
    }

    /**
     * Scan for artwork named like <videoFileName><artworkToken>.<artworkExtensions>
     *
     * @param movie
     * @return UNKNOWN or Absolute Path
     */
    protected String scanVideoArtwork(Movie movie, String artworkFilename) {
        return scanVideoArtwork(movie, artworkFilename, "");
    }

    /**
     * Scan for artwork named like <videoFileName><artworkToken>.<artworkExtensions>
     *
     * @param movie
     * @param additionalPath A sub-directory of the movie to scan
     * @return UNKNOWN or Absolute Path
     */
    protected String scanVideoArtwork(Movie movie, String artworkFilename, String additionalPath) {
        String parentPath = StringTools.appendToPath(FileTools.getParentFolder(movie.getFile()), StringUtils.trimToEmpty(additionalPath));
        return scanDirectoryForArtwork(artworkFilename, parentPath);
    }

    protected void setImagePlugin(String classNameString) {
        String className;
        if (StringTools.isNotValidString(classNameString)) {
            // Use the default image plugin
            className = PropertiesUtil.getProperty("mjb.image.plugin", "com.moviejukebox.plugin.DefaultImagePlugin");
        } else {
            className = classNameString;
        }

        try {
            Thread artThread = Thread.currentThread();
            ClassLoader cl = artThread.getContextClassLoader();
            Class<? extends MovieImagePlugin> pluginClass = cl.loadClass(className).asSubclass(MovieImagePlugin.class);
            artworkImagePlugin = pluginClass.newInstance();
            return;
        } catch (ClassNotFoundException ex) {
            logger.error(LOG_MESSAGE + "Error instanciating imagePlugin: " + className + " - class not found!");
            logger.error(SystemTools.getStackTrace(ex));
        } catch (InstantiationException ex) {
            logger.error(LOG_MESSAGE + "Failed instanciating imagePlugin: " + className);
            logger.error(SystemTools.getStackTrace(ex));
        } catch (IllegalAccessException ex) {
            logger.error(LOG_MESSAGE + "Unable instanciating imagePlugin: " + className);
            logger.error(SystemTools.getStackTrace(ex));
        }

        logger.error(LOG_MESSAGE + "Default plugin will be used instead.");
        // Use the background plugin for fanart, the image plugin for all others
        if (artworkType == ArtworkType.Fanart) {
            artworkImagePlugin = new DefaultBackgroundPlugin();
        } else {
            artworkImagePlugin = new DefaultImagePlugin();
        }
    }

    /**
     * Set the default artwork dimensions for use in the validate routine
     */
    private void setArtworkDimensions() {
        // This should return a value, but if it doesn't then we'll default to "posters"
        String dimensionType = getPropertyName();

        artworkWidth = PropertiesUtil.getIntProperty(dimensionType + ".width", "0");
        artworkHeight = PropertiesUtil.getIntProperty(dimensionType + ".height", "0");

        if ((artworkWidth == 0) || (artworkHeight == 0)) {
            // There was an issue with the correct properties, so use poster as a default.
            try {
                artworkWidth = PropertiesUtil.getIntProperty("posters.width", "400");
                artworkHeight = PropertiesUtil.getIntProperty("posters.height", "600");
            } catch (Exception ignore) {
                // Just in case there is an issue with the poster settings too!
                artworkWidth = 400;
                artworkHeight = 600;
            }
        }
    }

    /**
     * Set the list of artwork extensions to search for using a delimited string
     *
     * @param artworkExtensions
     */
    private void setArtworkExtensions(String extensions) {
        StringTokenizer st = new StringTokenizer(extensions, SPLITTER);
        while (st.hasMoreTokens()) {
            artworkExtensions.add(st.nextToken());
        }
    }

    /**
     * Set the list of folder artwork image names
     *
     * @param artworkImageName
     */
    private void setArtworkImageName(String artworkImageNameString) {
        StringTokenizer st = new StringTokenizer(artworkImageNameString, SPLITTER);

        while (st.hasMoreTokens()) {
            artworkImageName.add(st.nextToken());
        }
    }

    /**
     * Set the name for the artwork type to be used in logger messages
     *
     * @param artworkType The artwork type to instantiate the scanner for
     */
    private void setArtworkType(ArtworkType setArtworkType) {
        artworkType = setArtworkType;
        artworkTypeName = artworkType.toString().toLowerCase();

        // Set the default logger message
        LOG_MESSAGE = "ArtworkScanner (" + artworkType + "): ";
//        logger.debug(LOG_MESSAGE + "Using " + artworkType + " as the Artwork Type");  // XXX DEBUG
    }

    /**
     * Create a safe filename for the artwork
     *
     * @param movie
     * @return
     */
    @Override
    public String makeSafeArtworkFilename(Movie movie) {
        StringBuilder filename = new StringBuilder();

        filename.append(FileTools.makeSafeFilename(movie.getBaseName()));
        if (StringTools.isValidString(artworkToken)) {
            filename.append(artworkToken);
        }
        filename.append(".").append(artworkFormat);

        logger.debug(LOG_MESSAGE + "Safe filename: " + filename.toString());
        return filename.toString();
    }

    /**
     * Pretty print method for the debug property output
     *
     * @param propName
     * @param propValue
     * @param addTypeToOutput
     */
    protected final void debugProperty(String propName, Object propValue, boolean addTypeToOutput) {
        StringBuilder property = new StringBuilder(LOG_MESSAGE);
        property.append("DEBUG - '");
        if (addTypeToOutput) {
            property.append(artworkTypeName);
        }
        property.append(propName).append("' = ");
        property.append(propValue);
        logger.debug(property.toString());
    }

    /**
     * Pretty print method for the debug property output
     *
     * @param propName
     * @param propValue
     */
    protected final void debugProperty(String propName, Object propValue) {
        debugProperty(propName, propValue, Boolean.TRUE);
    }

    /**
     * Output the properties used by this scanner
     */
    public final void debugOutput() {
        debugProperty(" Required      ?", isSearchRequired());
        debugProperty(" Local Required?", isSearchLocal());
        debugProperty(" TV Download   ?", artworkDownloadTv);
        debugProperty(" Movie Download?", artworkDownloadMovie);
        debugProperty(".scanner.imageName", artworkImageName);
        debugProperty(".scanner.searchForExistingArtwork", artworkSearchLocal);
        debugProperty(".scanner.artworkToken", artworkToken);
        debugProperty(".format", artworkFormat);
        debugProperty(".scanner.Validate", artworkValidate);
        debugProperty(".scanner.ValidateMatch", artworkValidateMatch);
        debugProperty(".scanner.ValidateAspect", artworkValidateAspect);
        debugProperty(".scanner.artworkDirectory", artworkDirectory);
        debugProperty(".scanner.artworkPriority", artworkPriority);
        debugProperty(".movie.download", artworkDownloadMovie);
        debugProperty(".tv.download", artworkDownloadTv);
        debugProperty(".scanner.artworkExtensions", artworkExtensions);
        debugProperty(getPropertyName() + ".width", artworkWidth, Boolean.FALSE);
        debugProperty(getPropertyName() + ".height", artworkHeight, Boolean.FALSE);
    }

    /**
     * Return the name used in the properties file for this artwork type
     *
     * This is needed because of the disconnection between what was originally in the properties files and making it
     * generic enough for this scanner
     *
     * @return
     */
    protected final String getPropertyName() {
        return ArtworkScanner.getPropertyName(artworkType);
    }

    /**
     * Return the name used in the properties file for this artwork type
     *
     * This is needed because of the disconnection between what was originally in the properties files and making it
     * generic enough for this scanner
     *
     * @return
     */
    public static String getPropertyName(ArtworkType artworkType) {
        if (artworkType == ArtworkType.Poster) {
            return "posters";
        }

        if (artworkType == ArtworkType.Fanart) {
            return "";
        }

        if (artworkType == ArtworkType.Banner) {
            return "banners";
        }

        if (artworkType == ArtworkType.VideoImage) {
            return "videoimages";
        }

        return artworkType.toString().toLowerCase();
    }
}

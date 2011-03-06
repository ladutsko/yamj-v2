/*
 *      Copyright (c) 2004-2011 YAMJ Members
 *      http://code.google.com/p/moviejukebox/people/list 
 *  
 *      Web: http://code.google.com/p/moviejukebox/
 *  
 *      This software is licensed under a Creative Commons License
 *      See this page: http://code.google.com/p/moviejukebox/wiki/License
 *  
 *      For any reuse or distribution, you must make clear to others the 
 *      license terms of this work.  
 */
package com.moviejukebox.plugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.StringTokenizer;

import org.apache.xmlrpc.XmlRpcException;

import com.moviejukebox.model.Library;
import com.moviejukebox.model.Movie;
import com.moviejukebox.tools.PropertiesUtil;
import com.moviejukebox.tools.StringTools;

/**
 * The MovieMeterPlugin uses the XML-RPC API of www.moviemeter.nl (http://wiki.moviemeter.nl/index.php/API).
 * 
 * Version 0.1 : Initial release Version 0.2 : Fixed google search Version 0.3 : Fixed a problem when the moviemeter webservice returned no movie duration
 * (Issue 676) Version 0.4 : Fixed a problem when the moviemeter webservice returned no actors (Issue 677) Added extra checks if values returned from the
 * webservice doesn't exist Version 0.5 : Added Fanart download based on imdb id returned from moviemeter
 * 
 * @author RdeTuinman
 * 
 */
public class MovieMeterPlugin extends ImdbPlugin {

    public static String MOVIEMETER_PLUGIN_ID = "moviemeter";
    private MovieMeterPluginSession session;
    protected String preferredSearchEngine;
    private int preferredPlotLength;

    public MovieMeterPlugin() {
        super();

        preferredSearchEngine = PropertiesUtil.getProperty("moviemeter.id.search", "moviemeter");
        preferredPlotLength = PropertiesUtil.getIntProperty("plugin.plot.maxlength", "500");

        try {
            session = new MovieMeterPluginSession();
        } catch (XmlRpcException error) {
            final Writer eResult = new StringWriter();
            final PrintWriter printWriter = new PrintWriter(eResult);
            error.printStackTrace(printWriter);
            logger.severe("MovieMeterPlugin: " + eResult.toString());
        }

    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean scan(Movie mediaFile) {
        logger.finest("MovieMeterPlugin: Start fetching info from moviemeter.nl for : year=" + mediaFile.getYear() + ", title=" + mediaFile.getTitle());
        String moviemeterId = mediaFile.getId(MOVIEMETER_PLUGIN_ID);

        HashMap filmInfo = null;

        if (StringTools.isNotValidString(moviemeterId)) {
            logger.finest("MovieMeterPlugin: Preferred search engine for moviemeter id: " + preferredSearchEngine);
            if ("google".equalsIgnoreCase(preferredSearchEngine)) {
                // Get moviemeter website from google
                logger.finest("MovieMeterPlugin: Searching google.nl to get moviemeter.nl id");
                moviemeterId = getMovieMeterIdFromGoogle(mediaFile.getTitle(), mediaFile.getYear());
                logger.finest("MovieMeterPlugin: Returned id: " + moviemeterId);
                if (moviemeterId != Movie.UNKNOWN) {
                    filmInfo = session.getMovieDetailsById(Integer.parseInt(moviemeterId));
                }
            } else if ("none".equalsIgnoreCase(preferredSearchEngine)) {
                moviemeterId = Movie.UNKNOWN;
            } else {
                logger.finest("MovieMeterPlugin: Searching moviemeter.nl for title: " + mediaFile.getTitle());
                filmInfo = session.getMovieDetailsByTitleAndYear(mediaFile.getTitle(), mediaFile.getYear());
            }
        } else {
            logger.finest("MovieMeterPlugin: Searching moviemeter.nl for id: " + moviemeterId);
            filmInfo = session.getMovieDetailsById(Integer.parseInt(moviemeterId));
        }

        if (filmInfo != null) {
            mediaFile.setId(MOVIEMETER_PLUGIN_ID, filmInfo.get("filmId").toString());

            if (filmInfo.get("imdb") != null) {
                // if moviemeter returns the imdb id, add it to the mediaFile
                mediaFile.setId(IMDB_PLUGIN_ID, "tt" + filmInfo.get("imdb").toString());
                logger.finest("MovieMeterPlugin: Fetched imdb id: " + mediaFile.getId(IMDB_PLUGIN_ID));
            }

            if (!mediaFile.isOverrideTitle()) {
                if (filmInfo.get("title") != null) {
                    mediaFile.setTitle(filmInfo.get("title").toString());
                    mediaFile.setOriginalTitle(filmInfo.get("title").toString());
                }
                logger.finest("MovieMeterPlugin: Fetched title: " + mediaFile.getTitle());
            }

            if (mediaFile.getRating() == -1) {
                if (filmInfo.get("average") != null) {
                    mediaFile.setRating(Math.round(Float.parseFloat(filmInfo.get("average").toString()) * 20));
                }
                logger.finest("MovieMeterPlugin: Fetched rating: " + mediaFile.getRating());
            }

            if (mediaFile.getReleaseDate().equals(Movie.UNKNOWN)) {
                Object[] dates = (Object[])filmInfo.get("dates_cinema");
                if (dates != null && dates.length > 0) {
                    HashMap dateshm = (HashMap)dates[0];
                    mediaFile.setReleaseDate(dateshm.get("date").toString());
                    logger.finest("MovieMeterPlugin: Fetched releasedate: " + mediaFile.getReleaseDate());
                }
            }

            if (mediaFile.getRuntime().equals(Movie.UNKNOWN)) {
                if (filmInfo.get("durations") != null) {
                    Object[] durationsArray = (Object[])filmInfo.get("durations");
                    if (durationsArray.length > 0) {
                        HashMap durations = (HashMap)(durationsArray[0]);
                        mediaFile.setRuntime(durations.get("duration").toString());
                    }
                }
                logger.finest("MovieMeterPlugin: Fetched runtime: " + mediaFile.getRuntime());
            }

            if (mediaFile.getCountry().equals(Movie.UNKNOWN)) {
                if (filmInfo.get("countries_text") != null) {
                    mediaFile.setCountry(filmInfo.get("countries_text").toString());
                }
                logger.finest("MovieMeterPlugin: Fetched country: " + mediaFile.getCountry());
            }

            if (mediaFile.getGenres().isEmpty()) {
                if (filmInfo.get("genres") != null) {
                    Object[] genres = (Object[])filmInfo.get("genres");
                    for (int i = 0; i < genres.length; i++) {
                        mediaFile.addGenre(Library.getIndexingGenre(genres[i].toString()));
                    }
                }
                logger.finest("MovieMeterPlugin: Fetched genres: " + mediaFile.getGenres().toString());
            }

            if (mediaFile.getPlot().equals(Movie.UNKNOWN)) {
                if (filmInfo.get("plot") != null) {
                    String tmpPlot = filmInfo.get("plot").toString();
                    tmpPlot= StringTools.trimToLength(tmpPlot, preferredPlotLength, true, plotEnding);
                    mediaFile.setPlot(tmpPlot);
                }
            }

            if (!mediaFile.isOverrideYear()) {
                if (filmInfo.get("year") != null) {
                    mediaFile.setYear(filmInfo.get("year").toString());
                }
                logger.finest("MovieMeterPlugin: Fetched year: " + mediaFile.getYear());
            }

            if (mediaFile.getCast().isEmpty()) {
                if (filmInfo.get("actors") != null) {
                    // If no actor is known, false is returned instead of an array
                    // This results in a ClassCastException
                    // So first check the Class, before casting it to an Object array
                    if (filmInfo.get("actors").getClass().equals(Object[].class)) {
                        Object[] actors = (Object[])filmInfo.get("actors");
                        for (int i = 0; i < actors.length; i++) {
                            mediaFile.addActor((String)(((HashMap)actors[i]).get("name")));
                        }
                    }
                }
                logger.finest("MovieMeterPlugin: Fetched actors: " + mediaFile.getCast().toString());
            }

            if (mediaFile.getDirector().equals(Movie.UNKNOWN)) {
                Object[] directors = (Object[])filmInfo.get("directors");
                if (directors != null && directors.length > 0) {
                    HashMap directorshm = (HashMap)directors[0];
                    mediaFile.addDirector(directorshm.get("name").toString());
                    logger.finest("MovieMeterPlugin: Fetched director: " + mediaFile.getDirector());
                }
            }

            if (downloadFanart && StringTools.isNotValidString(mediaFile.getFanartURL())) {
                mediaFile.setFanartURL(getFanartURL(mediaFile));
                if (StringTools.isValidString(mediaFile.getFanartURL())) {
                    mediaFile.setFanartFilename(mediaFile.getBaseName() + fanartToken + ".jpg");
                }
            }

        } else {
            logger.finest("MovieMeterPlugin: No info found");
        }

        return true;
    }

    /**
     * Searches www.google.nl for the moviename and retreives the movie id for www.moviemeter.nl.
     * 
     * Only used when moviemeter.id.search=google
     * 
     * @param movieName
     * @param year
     * @return
     */
    private String getMovieMeterIdFromGoogle(String movieName, String year) {
        try {
            StringBuffer sb = new StringBuffer("http://www.google.nl/search?hl=nl&q=");
            sb.append(URLEncoder.encode(movieName, "UTF-8"));

            if (StringTools.isValidString(year)) {
                sb.append("+%28").append(year).append("%29");
            }

            sb.append("+site%3Amoviemeter.nl/film");

            String xml = webBrowser.request(sb.toString());
            int beginIndex = xml.indexOf("www.moviemeter.nl/film/");
            StringTokenizer st = new StringTokenizer(xml.substring(beginIndex + 23), "/\"");
            String moviemeterId = st.nextToken();

            if (isInteger(moviemeterId)) {
                return moviemeterId;
            } else {
                return Movie.UNKNOWN;
            }

        } catch (Exception error) {
            logger.severe("MovieMeterPlugin: Failed retreiving moviemeter Id from Google for movie : " + movieName);
            logger.severe("MovieMeterPlugin: Error : " + error.getMessage());
            return Movie.UNKNOWN;
        }
    }

    private boolean isInteger(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    @Override
    public void scanNFO(String nfo, Movie movie) {
        logger.finest("MovieMeterPlugin: Scanning NFO for Moviemeter Id");
        int beginIndex = nfo.indexOf("www.moviemeter.nl/film/");
        if (beginIndex != -1) {
            StringTokenizer st = new StringTokenizer(nfo.substring(beginIndex + 23), "/ \n,:!&é\"'(--è_çà)=$");
            movie.setId(MOVIEMETER_PLUGIN_ID, st.nextToken());
            logger.finer("MovieMeterPlugin: Moviemeter Id found in nfo = " + movie.getId(MOVIEMETER_PLUGIN_ID));
        } else {
            logger.finer("MovieMeterPlugin: No Moviemeter Id found in nfo !");
        }
    }
}
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
package com.moviejukebox.plugin;

import com.moviejukebox.model.Filmography;
import com.moviejukebox.model.Movie;
import com.moviejukebox.scanner.MovieFilenameScanner;
import com.moviejukebox.scanner.artwork.FanartScanner;
import com.moviejukebox.tools.OverrideTools;
import com.moviejukebox.tools.PropertiesUtil;
import com.moviejukebox.tools.StringTools;
import com.moviejukebox.tools.ThreadExecutor;
import com.moviejukebox.tools.WebBrowser;
import com.moviejukebox.tools.cache.CacheMemory;
import com.omertron.themoviedbapi.MovieDbException;
import com.omertron.themoviedbapi.TheMovieDbApi;
import com.omertron.themoviedbapi.model.Artwork;
import com.omertron.themoviedbapi.model.Collection;
import com.omertron.themoviedbapi.model.CollectionInfo;
import com.omertron.themoviedbapi.model.Genre;
import com.omertron.themoviedbapi.model.Language;
import com.omertron.themoviedbapi.model.MovieDb;
import com.omertron.themoviedbapi.model.Person;
import com.omertron.themoviedbapi.model.PersonCredit;
import com.omertron.themoviedbapi.model.PersonType;
import com.omertron.themoviedbapi.model.ProductionCompany;
import com.omertron.themoviedbapi.model.ProductionCountry;
import com.omertron.themoviedbapi.model.ReleaseInfo;
import com.omertron.themoviedbapi.results.TmdbResultsList;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.pojava.datetime2.DateTime;

/**
 * @author Stuart.Boston
 * @version 2.0 (18th October 2010)
 */
public class TheMovieDbPlugin implements MovieDatabasePlugin {

    private static final Logger LOG = Logger.getLogger(TheMovieDbPlugin.class);
    private static final String LOG_MESSAGE = "TheMovieDbPlugin: ";
    public static final String TMDB_PLUGIN_ID = "themoviedb";
    public static final String IMDB_PLUGIN_ID = "imdb";
    private static final String webhost = "themoviedb.org";
    private static final String API_KEY = PropertiesUtil.getProperty("API_KEY_TheMovieDB");
    private TheMovieDbApi TMDb;
    private String languageCode;
    private String countryCode;
    private boolean downloadFanart;
    private static String fanartToken = PropertiesUtil.getProperty("mjb.scanner.fanartToken", ".fanart");
    private String fanartExtension;
    public static final boolean INCLUDE_ADULT = PropertiesUtil.getBooleanProperty("themoviedb.includeAdult", Boolean.FALSE);
    public static final int SEARCH_MATCH = PropertiesUtil.getIntProperty("themoviedb.searchMatch", 3);
    private static final String LANGUAGE_DELIMITER = PropertiesUtil.getProperty("mjb.language.delimiter", Movie.SPACE_SLASH_SPACE);
    private static final boolean AUTO_COLLECTION = PropertiesUtil.getBooleanProperty("themoviedb.collection", Boolean.FALSE);
    public static final String CACHE_COLLECTION = "Collection";
    public static final String CACHE_COLLECTION_IMAGES = "CollectionImages";
    private int preferredBiographyLength;
    private int preferredFilmographyMax;

    public TheMovieDbPlugin() {
        try {
            TMDb = new TheMovieDbApi(API_KEY);
        } catch (MovieDbException ex) {
            LOG.warn(LOG_MESSAGE + "Failed to initialise TheMovieDB API: " + ex.getMessage());
            return;
        }

        // Set the proxy
        TMDb.setProxy(WebBrowser.getMjbProxyHost(), WebBrowser.getMjbProxyPort(), WebBrowser.getMjbProxyUsername(), WebBrowser.getMjbProxyPassword());

        // Set the timeouts
        TMDb.setTimeout(WebBrowser.getMjbTimeoutConnect(), WebBrowser.getMjbTimeoutRead());

        decodeLanguage();

        downloadFanart = PropertiesUtil.getBooleanProperty("fanart.movie.download", Boolean.FALSE);
        fanartExtension = PropertiesUtil.getProperty("fanart.format", "jpg");

        preferredBiographyLength = PropertiesUtil.getIntProperty("plugin.biography.maxlength", 500);
        preferredFilmographyMax = PropertiesUtil.getIntProperty("plugin.filmography.max", 20);
    }

    /**
     * Decode the language code property into language and country
     */
    private void decodeLanguage() {
        String language = PropertiesUtil.getProperty("themoviedb.language", "en");
        String country = PropertiesUtil.getProperty("themoviedb.country", "");     // Don't default this as we might get it from the language (old setting)

        if (language.length() > 2) {
            if (StringUtils.isBlank(country)) {
                // Guess that the last 2 characters of the language code is the country code.
                country = language.substring(language.length() - 2).toUpperCase();
            }
            language = language.substring(0, 2).toLowerCase();
        }

        // Default the country to US
        if (StringUtils.isBlank(country)) {
            country = "US";
        }

        setLanguageCode(language);
        setCountryCode(country);

        LOG.debug(LOG_MESSAGE + "Using '" + language + "' as the language code");
        LOG.debug(LOG_MESSAGE + "Using '" + country + "' as the country code");
    }

    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    @Override
    public String getPluginID() {
        return TMDB_PLUGIN_ID;
    }

    @Override
    public boolean scan(Movie movie) {
        String imdbID = movie.getId(IMDB_PLUGIN_ID);
        String tmdbID = movie.getId(TMDB_PLUGIN_ID);
        List<MovieDb> movieList;
        List<ReleaseInfo> movieReleaseInfo = new ArrayList<ReleaseInfo>();
        List<Person> moviePeople = new ArrayList<Person>();
        MovieDb moviedb = null;
        boolean retval = false;

        ThreadExecutor.enterIO(webhost);
        try {
            // First look to see if we have a TMDb ID as this will make looking the film up easier
            if (StringTools.isValidString(tmdbID)) {
                // Search based on TMdb ID
                LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": Using TMDb ID (" + tmdbID + ") for " + movie.getBaseName());
                try {
                    moviedb = TMDb.getMovieInfo(Integer.parseInt(tmdbID), languageCode);
                } catch (MovieDbException ex) {
                    LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": Failed to get movie info using TMDB ID: " + tmdbID + " - " + ex.getMessage());
                    moviedb = null;
                }
            }

            if (moviedb == null && StringTools.isValidString(imdbID)) {
                // Search based on IMDb ID
                LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": Using IMDb ID (" + imdbID + ") for " + movie.getBaseName());
                try {
                    moviedb = TMDb.getMovieInfoImdb(imdbID, languageCode);
                    tmdbID = String.valueOf(moviedb.getId());
                    if (StringTools.isNotValidString(tmdbID)) {
                        LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": No TMDb ID found for movie!");
                    }
                } catch (MovieDbException ex) {
                    LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": Failed to get movie info using IMDB ID: " + imdbID + " - " + ex.getMessage());
                    moviedb = null;
                }
            }

            if (moviedb == null) {
                LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": No IDs provided for movie, search using title & year");
                try {
                    // Search using movie name
                    int movieYear = 0;
                    if (StringTools.isValidString(movie.getYear()) && StringUtils.isNumeric(movie.getYear())) {
                        movieYear = Integer.parseInt(movie.getYear());
                    }

                    LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": Using '" + movie.getTitle() + "' & '" + movieYear + "' to locate movie information");
                    TmdbResultsList<MovieDb> result = TMDb.searchMovie(movie.getTitle(), movieYear, languageCode, INCLUDE_ADULT, 0);
                    movieList = result.getResults();
                    LOG.debug(LOG_MESSAGE + movie.getBaseName() + ": Found " + result.getResults().size() + " potential matches");

                    // Iterate over the list until we find a match
                    for (MovieDb m : movieList) {
                        LOG.debug(LOG_MESSAGE + "Checking " + m.getTitle() + " (" + m.getReleaseDate().substring(0, 4) + ")");
                        if (TheMovieDbApi.compareMovies(m, movie.getTitle(), Integer.toString(movieYear), SEARCH_MATCH)) {
                            moviedb = m;
                            break;
                        }

                        // See if the original title is different and then compare it too
                        if (!movie.getTitle().equals(movie.getOriginalTitle())
                                && TheMovieDbApi.compareMovies(m, movie.getOriginalTitle(), Integer.toString(movieYear))) {
                            moviedb = m;
                            break;
                        }
                    }
                } catch (MovieDbException ex) {
                    LOG.debug(LOG_MESSAGE + "Failed to get movie info for " + movie.getTitle() + " - " + ex.getMessage());
                    moviedb = null;
                }
            }

            if (moviedb == null) {
                LOG.debug(LOG_MESSAGE + "Movie " + movie.getBaseName() + " not found!");
                LOG.debug(LOG_MESSAGE + "Try using a NFO file to specify the movie");
                return false;
            } else {
                try {
                    // Get the full information on the film
                    moviedb = TMDb.getMovieInfo(moviedb.getId(), languageCode);
                } catch (MovieDbException ex) {
                    LOG.debug(LOG_MESSAGE + "Failed to download remaining information for " + movie.getBaseName());
                }
                LOG.debug(LOG_MESSAGE + "Found id (" + moviedb.getId() + ") for " + moviedb.getTitle());
            }


            try {
                // Get the release information
                movieReleaseInfo = TMDb.getMovieReleaseInfo(moviedb.getId(), countryCode).getResults();
            } catch (MovieDbException ex) {
                LOG.debug(LOG_MESSAGE + "Failed to get release information: " + ex.getMessage(), ex);
            }

            try {
                // Get the cast information
                moviePeople = TMDb.getMovieCasts(moviedb.getId()).getResults();
            } catch (MovieDbException ex) {
                LOG.debug(LOG_MESSAGE + "Failed to get cast information: " + ex.getMessage(), ex);
            }
        } finally {
            // the rest is not web search anymore
            ThreadExecutor.leaveIO();
        }

        if (moviedb != null) {
            if (moviedb.getId() > 0) {
                movie.setMovieType(Movie.TYPE_MOVIE);
            }

            if (StringTools.isValidString(moviedb.getTitle())) {
                copyMovieInfo(moviedb, movie);
                retval = true;
            }

            // Set the release information
            if (movieReleaseInfo.size() > 0 && OverrideTools.checkOverwriteCertification(movie, TMDB_PLUGIN_ID)) {
                LOG.debug(LOG_MESSAGE + "Found release information: " + movieReleaseInfo.get(0).toString());
                movie.setCertification(movieReleaseInfo.get(0).getCertification(), TMDB_PLUGIN_ID);
            }

            // Add the cast information
            // TODO: Add the people to the cast/crew
            if (moviePeople.size() > 0) {
                List<String> newActors = new ArrayList<String>();
                List<String> newDirectors = new ArrayList<String>();
                List<String> newWriters = new ArrayList<String>();

                LOG.debug(LOG_MESSAGE + "Adding " + moviePeople.size() + " people to the cast list");
                for (Person person : moviePeople) {
                    if (person.getPersonType() == PersonType.CAST) {
                        LOG.trace(LOG_MESSAGE + "Adding cast member " + person.toString());
                        newActors.add(person.getName());
                    } else if (person.getPersonType() == PersonType.CREW) {
                        LOG.trace(LOG_MESSAGE + "Adding crew member " + person.toString());
                        if ("Directing".equalsIgnoreCase(person.getDepartment())) {
                            LOG.trace(LOG_MESSAGE + person.getName() + " is a Director");
                            newDirectors.add(person.getName());
                        } else if ("Writing".equalsIgnoreCase(person.getDepartment())) {
                            LOG.trace(LOG_MESSAGE + person.getName() + " is a Writer");
                            newWriters.add(person.getName());
                        } else {
                            LOG.trace(LOG_MESSAGE + "Unknown job  " + person.getJob() + " for " + person.toString());
                        }
                    } else {
                        LOG.trace(LOG_MESSAGE + "Unknown person type " + person.getPersonType() + " for " + person.toString());
                    }
                }

                if (OverrideTools.checkOverwriteDirectors(movie, TMDB_PLUGIN_ID)) {
                    movie.setDirectors(newDirectors, TMDB_PLUGIN_ID);
                }
                if (OverrideTools.checkOverwritePeopleDirectors(movie, TMDB_PLUGIN_ID)) {
                    movie.setPeopleDirectors(newDirectors, TMDB_PLUGIN_ID);
                }
                if (OverrideTools.checkOverwriteWriters(movie, TMDB_PLUGIN_ID)) {
                    movie.setWriters(newWriters, TMDB_PLUGIN_ID);
                }
                if (OverrideTools.checkOverwritePeopleWriters(movie, TMDB_PLUGIN_ID)) {
                    movie.setPeopleWriters(newWriters, TMDB_PLUGIN_ID);
                }
                if (OverrideTools.checkOverwriteActors(movie, TMDB_PLUGIN_ID)) {
                    movie.setCast(newActors, TMDB_PLUGIN_ID);
                }
                if (OverrideTools.checkOverwritePeopleActors(movie, TMDB_PLUGIN_ID)) {
                    movie.setPeopleCast(newActors, TMDB_PLUGIN_ID);
                }
            } else {
                LOG.debug(LOG_MESSAGE + "No cast or crew members found");
            }

            // Update TheMovieDb Id if needed
            if (StringTools.isNotValidString(movie.getId(TMDB_PLUGIN_ID))) {
                movie.setId(TMDB_PLUGIN_ID, moviedb.getId());
            }

            // Update IMDb Id if needed
            if (StringTools.isNotValidString(movie.getId(IMDB_PLUGIN_ID))) {
                movie.setId(IMDB_PLUGIN_ID, moviedb.getImdbID());
            }

            // Create the auto sets for movies and not extras
            if (AUTO_COLLECTION && !movie.isExtra()) {
                Collection coll = moviedb.getBelongsToCollection();
                if (coll != null) {
                    LOG.debug(LOG_MESSAGE + movie.getTitle() + " belongs to a collection: '" + coll.getName() + "'");
                    CollectionInfo collInfo = getCollectionInfo(coll.getId(), languageCode);
                    if (collInfo != null) {
                        movie.addSet(collInfo.getName());
                        movie.setId(CACHE_COLLECTION, coll.getId());
                    } else {
                        LOG.debug(LOG_MESSAGE + "Failed to get collection information!");
                    }
                }
            }
        }

        if (downloadFanart && StringTools.isNotValidString(movie.getFanartURL())) {
            movie.setFanartURL(getFanartURL(movie));
            if (StringTools.isValidString(movie.getFanartURL())) {
                movie.setFanartFilename(movie.getBaseName() + fanartToken + "." + fanartExtension);
            }
        }
        return retval;
    }

    /**
     * Copy the movie info from the MovieDB bean to the YAMJ movie bean
     *
     * @param moviedb The MovieDB source
     * @param movie The YAMJ target
     * @return The altered movie bean
     */
    private void copyMovieInfo(MovieDb moviedb, Movie movie) {

        // TMDb ID
        movie.setId(TMDB_PLUGIN_ID, moviedb.getId());

        // IMDb ID
        movie.setId(IMDB_PLUGIN_ID, moviedb.getImdbID());

        // title
        if (OverrideTools.checkOverwriteTitle(movie, TMDB_PLUGIN_ID)) {
            movie.setTitle(moviedb.getTitle(), TMDB_PLUGIN_ID);
        }

        // original title
        if (OverrideTools.checkOverwriteOriginalTitle(movie, TMDB_PLUGIN_ID)) {
            movie.setOriginalTitle(moviedb.getOriginalTitle(), TMDB_PLUGIN_ID);
        }

        // plot
        if (OverrideTools.checkOverwritePlot(movie, TMDB_PLUGIN_ID)) {
            movie.setPlot(moviedb.getOverview(), TMDB_PLUGIN_ID);
        }

        // outline
        if (OverrideTools.checkOverwriteOutline(movie, TMDB_PLUGIN_ID)) {
            movie.setOutline(moviedb.getOverview(), TMDB_PLUGIN_ID);
        }

        // rating
        if (overwriteCheck(String.valueOf(moviedb.getVoteAverage()), String.valueOf(movie.getRating()))) {
            try {
                float rating = moviedb.getVoteAverage() * 10; // Convert rating to integer
                movie.addRating(TMDB_PLUGIN_ID, (int) rating);
            } catch (Exception error) {
                LOG.debug(LOG_MESSAGE + "Error converting rating for " + movie.getBaseName());
            }
        }

        // release date
        if (OverrideTools.checkOverwriteReleaseDate(movie, TMDB_PLUGIN_ID)) {
            movie.setReleaseDate(moviedb.getReleaseDate(), TMDB_PLUGIN_ID);
        }

        // year
        if (OverrideTools.checkOverwriteYear(movie, TMDB_PLUGIN_ID)) {
            String year = moviedb.getReleaseDate();
            // Check if this is the default year and skip it
            if (!"1900-01-01".equals(year)) {
                year = (new DateTime(year)).toString("yyyy");
                movie.setYear(year, TMDB_PLUGIN_ID);
            }
        }

        // runtime
        if (OverrideTools.checkOverwriteRuntime(movie, TMDB_PLUGIN_ID)) {
            movie.setRuntime(String.valueOf(moviedb.getRuntime()), TMDB_PLUGIN_ID);
        }

        // tagline
        if (OverrideTools.checkOverwriteTagline(movie, TMDB_PLUGIN_ID)) {
            movie.setTagline(moviedb.getTagline(), TMDB_PLUGIN_ID);
        }

        // Country
        if (OverrideTools.checkOverwriteCountry(movie, TMDB_PLUGIN_ID)) {
            List<ProductionCountry> countries = moviedb.getProductionCountries();
            if (!countries.isEmpty()) {
                movie.setCountry(countries.get(0).getName(), TMDB_PLUGIN_ID);
            }
        }

        // Company
        if (OverrideTools.checkOverwriteCompany(movie, TMDB_PLUGIN_ID)) {
            List<ProductionCompany> studios = moviedb.getProductionCompanies();
            if (!studios.isEmpty()) {
                String studio = studios.get(0).getName();
                movie.setCompany(studio, TMDB_PLUGIN_ID);
            }
        }

        // Language
        if (moviedb.getSpokenLanguages().size() > 0 && OverrideTools.checkOverwriteLanguage(movie, TMDB_PLUGIN_ID)) {
            StringBuilder movieLanguage = new StringBuilder();

            String isoCode = moviedb.getSpokenLanguages().get(0).getIsoCode();
            if (StringTools.isValidString(isoCode)) {
                movieLanguage.append(MovieFilenameScanner.determineLanguage(isoCode));
            }

            if (moviedb.getSpokenLanguages().size() > 1) {
                for (Language lang : moviedb.getSpokenLanguages()) {
                    if (movieLanguage.length() > 0) {
                        movieLanguage.append(LANGUAGE_DELIMITER);
                    }
                    movieLanguage.append(MovieFilenameScanner.determineLanguage(lang.getIsoCode()));
                }
            }

            movie.setLanguage(movieLanguage.toString(), TMDB_PLUGIN_ID);
        }

        // Genres
        if (OverrideTools.checkOverwriteGenres(movie, TMDB_PLUGIN_ID)) {
            List<String> newGenres = new ArrayList<String>();
            for (Genre genre : moviedb.getGenres()) {
                newGenres.add(genre.getName());
            }
            movie.setGenres(newGenres, TMDB_PLUGIN_ID);
        }
    }

    /**
     * Checks to see if the source string is null or "UNKNOWN" and that target string ISN'T null or "UNKNOWN"
     *
     * @param sourceString The source string to check
     * @param targetString The destination string to check
     * @return True if valid to overwrite
     */
    private boolean overwriteCheck(String sourceString, String targetString) {
        // false if the source is null or UNKNOWN
        if (StringTools.isValidString(sourceString)) {
            // sourceString is valid, check target string IS null OR UNKNOWN
            if (StringTools.isNotValidString(targetString) || targetString.equals("-1")) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public boolean scanNFO(String nfo, Movie movie) {
        int beginIndex;

        boolean result = Boolean.FALSE;

        if (StringTools.isValidString(movie.getId(TMDB_PLUGIN_ID))) {
            LOG.debug(LOG_MESSAGE + "TheMovieDb ID exists for " + movie.getBaseName() + " = " + movie.getId(TMDB_PLUGIN_ID));
            result = Boolean.TRUE;
        } else {
            LOG.debug(LOG_MESSAGE + "Scanning NFO for TheMovieDb ID");
            beginIndex = nfo.indexOf("/movie/");
            if (beginIndex != -1) {
                StringTokenizer st = new StringTokenizer(nfo.substring(beginIndex + 7), "/ \n,:!&é\"'(--è_çà)=$");
                movie.setId(TMDB_PLUGIN_ID, st.nextToken());
                LOG.debug(LOG_MESSAGE + "TheMovieDb ID found in NFO = " + movie.getId(TMDB_PLUGIN_ID));
                result = Boolean.TRUE;
            } else {
                LOG.debug(LOG_MESSAGE + "No TheMovieDb ID found in NFO!");
            }
        }

        // We might as well look for the IMDb ID as well
        if (StringTools.isValidString(movie.getId(IMDB_PLUGIN_ID))) {
            LOG.debug(LOG_MESSAGE + "IMDB ID exists for " + movie.getBaseName() + " = " + movie.getId(IMDB_PLUGIN_ID));
            result = Boolean.TRUE;
        } else {
            beginIndex = nfo.indexOf("/tt");
            if (beginIndex != -1) {
                StringTokenizer st = new StringTokenizer(nfo.substring(beginIndex + 1), "/ \n,:!&é\"'(--è_çà)=$");
                movie.setId(ImdbPlugin.IMDB_PLUGIN_ID, st.nextToken());
                LOG.debug(LOG_MESSAGE + "IMDB ID found in nfo = " + movie.getId(ImdbPlugin.IMDB_PLUGIN_ID));
            } else {
                beginIndex = nfo.indexOf("/Title?");
                if (beginIndex != -1 && beginIndex + 7 < nfo.length()) {
                    StringTokenizer st = new StringTokenizer(nfo.substring(beginIndex + 7), "/ \n,:!&é\"'(--è_çà)=$");
                    movie.setId(ImdbPlugin.IMDB_PLUGIN_ID, "tt" + st.nextToken());
                    LOG.debug(LOG_MESSAGE + "IMDB ID found in NFO = " + movie.getId(ImdbPlugin.IMDB_PLUGIN_ID));
                }
            }
        }
        return result;
    }

    @Override
    public void scanTVShowTitles(Movie movie) {
        // TheMovieDB.org does not have any TV Shows (yet), so just return
    }

    /**
     * Locate the FanartURL for the movie. This should probably be skipped as this uses TheMovieDb.org anyway
     *
     * @param movie Movie bean for the movie to locate
     * @return The URL of the fanart
     */
    protected String getFanartURL(Movie movie) {
        return FanartScanner.getFanartURL(movie);
    }

    /**
     * Search for information on the person
     *
     * @param person
     * @return
     */
    @Override
    public boolean scan(com.moviejukebox.model.Person person) {
        String id = getPersonId(person);

        if (StringTools.isValidString(id)) {
            int tmdbId = Integer.parseInt(id);
            try {
                com.omertron.themoviedbapi.model.Person tmdbPerson = TMDb.getPersonInfo(tmdbId);
                person.setName(tmdbPerson.getName());

                person.setBiography(StringUtils.abbreviate(tmdbPerson.getBiography(), preferredBiographyLength));
                person.setId(IMDB_PLUGIN_ID, tmdbPerson.getImdbId());
                person.setUrl("http://www.themoviedb.org/person/" + tmdbId);

                List<String> akas = tmdbPerson.getAka();
                if (akas != null && !akas.isEmpty()) {
                    // Set the birthname to be the first aka
                    person.setBirthName(akas.get(0));

                    // Add the akas
                    for (String aka : tmdbPerson.getAka()) {
                        person.addAka(aka);
                    }
                }

                if (StringTools.isValidString(tmdbPerson.getBirthday())) {
                    // Look for the death day and append that as well
                    if (StringTools.isValidString(tmdbPerson.getDeathday())) {
                        person.setYear(tmdbPerson.getBirthday() + "/" + tmdbPerson.getDeathday());
                    } else {
                        person.setYear(tmdbPerson.getBirthday());
                    }
                }

                person.setBirthPlace(tmdbPerson.getBirthplace());

                URL url = TMDb.createImageUrl(tmdbPerson.getProfilePath(), "original");
                person.setPhotoURL(url.toString());
                person.setPhotoFilename();

                // Filmography
                TmdbResultsList<PersonCredit> results = TMDb.getPersonCredits(tmdbId);
                person.setKnownMovies(results.getTotalResults());

                int count = 1;
                for (PersonCredit credit : results.getResults()) {
                    if (count++ > preferredFilmographyMax) {
                        // We have enough films
                        LOG.trace(LOG_MESSAGE + "Reached limit of " + preferredFilmographyMax + " films for " + person.getName() + ". Total found: " + results.getTotalResults());
                        break;
                    }

                    Filmography film = new Filmography();
                    film.setId(TMDB_PLUGIN_ID, Integer.toString(credit.getMovieId()));
                    film.setName(credit.getMovieTitle());
                    film.setOriginalTitle(credit.getMovieOriginalTitle());
                    film.setYear(credit.getReleaseDate());
                    film.setJob(credit.getJob());
                    film.setCharacter(credit.getCharacter());
                    film.setDepartment();
                    film.setUrl("www.themoviedb.org/movie/" + tmdbId);
                    person.addFilm(film);
                }

                // Update the version
                int version = person.getVersion();
                person.setVersion(++version);

                return Boolean.TRUE;
            } catch (MovieDbException ex) {
                LOG.warn(LOG_MESSAGE + "Failed to get information on " + person.getName() + " (" + tmdbId + "), error: " + ex.getMessage(), ex);
                return Boolean.FALSE;
            }
        } else {
            return Boolean.FALSE;
        }
    }

    /**
     * Locate and return the TMDB person ID from the person object
     *
     * @param person
     * @return
     */
    public String getPersonId(com.moviejukebox.model.Person person) {
        String tmdbId = person.getId(TMDB_PLUGIN_ID);
        if (StringTools.isNotValidString(tmdbId) && StringTools.isValidString(person.getName())) {
            // Look for the ID using the person's name
            tmdbId = getPersonId(person.getName());
            if (StringTools.isValidString(tmdbId)) {
                LOG.info(LOG_MESSAGE + person.getName() + ": ID found '" + tmdbId + "'");
                person.setId(TMDB_PLUGIN_ID, tmdbId);
            } else {
                LOG.warn(LOG_MESSAGE + "No ID found for " + person.getName());
            }
        }
        return tmdbId;
    }

    /**
     * Attempt to find the TMDB ID for a person using their full name
     *
     * @param name
     * @return
     */
    public String getPersonId(String name) {
        String tmdbId = "";
        com.omertron.themoviedbapi.model.Person closestPerson = null;
        int closestMatch = Integer.MAX_VALUE;
        boolean foundPerson = Boolean.FALSE;
        boolean includeAdult = PropertiesUtil.getBooleanProperty("themoviedb.includeAdult", Boolean.FALSE);

        try {
            TmdbResultsList<com.omertron.themoviedbapi.model.Person> results = TMDb.searchPeople(name, includeAdult, 0);
            LOG.info(LOG_MESSAGE + "Found " + results.getResults().size() + " person results for " + name);
            for (com.omertron.themoviedbapi.model.Person person : results.getResults()) {
                if (name.equalsIgnoreCase(person.getName())) {
                    tmdbId = String.valueOf(person.getId());
                    foundPerson = Boolean.TRUE;
                    break;
                } else {
                    LOG.trace(LOG_MESSAGE + "Checking " + name + " against " + person.getName());
                    int lhDistance = StringUtils.getLevenshteinDistance(name, person.getName());
                    LOG.trace(LOG_MESSAGE + name + ": Current closest match is " + closestMatch + ", this match is " + lhDistance);
                    if (lhDistance < closestMatch) {
                        LOG.trace(LOG_MESSAGE + name + ": TMDB ID " + person.getId() + " is a better match");
                        closestMatch = lhDistance;
                        closestPerson = person;
                    }
                }
            }

            if (foundPerson) {
                LOG.debug(LOG_MESSAGE + name + ": Matched against TMDB ID: " + tmdbId);
            } else if (closestMatch < Integer.MAX_VALUE && closestPerson != null) {
                tmdbId = String.valueOf(closestPerson.getId());
                LOG.debug(LOG_MESSAGE + name + ": Closest match is '" + closestPerson.getName() + "' differing by " + closestMatch + " characters");
            } else {
                LOG.debug(LOG_MESSAGE + name + ": No match found");
            }
        } catch (MovieDbException ex) {
            LOG.warn("Failed to get information on '" + name + "', error: " + ex.getMessage(), ex);
        }
        return tmdbId;
    }

    /**
     * Get the Collection information from the cache or online
     *
     * @param collectionId
     * @return
     */
    public CollectionInfo getCollectionInfo(int collectionId) {
        return getCollectionInfo(collectionId, languageCode);
    }

    /**
     * Get the Collection information from the cache or online
     *
     * @param collectionId
     * @param languageCode
     * @return
     */
    public CollectionInfo getCollectionInfo(int collectionId, String languageCode) {
        String cacheKey = getCollectionCacheKey(collectionId, languageCode);
        CollectionInfo collInfo = (CollectionInfo) CacheMemory.getFromCache(cacheKey);

        if (collInfo == null) {
            // Not found in cache, so look online
            ThreadExecutor.enterIO(webhost);
            try {
                collInfo = TMDb.getCollectionInfo(collectionId, languageCode);
                if (collInfo != null) {
                    URL newUrl;

                    // Update the URL to be the full URL
                    if (collInfo.getPosterPath() != null) {
                        newUrl = TMDb.createImageUrl(collInfo.getPosterPath(), "original");
                        collInfo.setPosterPath(newUrl.toString());
                    }

                    // Update the URL to be the full URL
                    if (collInfo.getBackdropPath() != null) {
                        newUrl = TMDb.createImageUrl(collInfo.getBackdropPath(), "original");
                        collInfo.setBackdropPath(newUrl.toString());
                    }

                    // Add to the cache
                    CacheMemory.addToCache(cacheKey, collInfo);
                }
            } catch (Exception error) {
                LOG.warn(LOG_MESSAGE + "Error getting CollectionInfo: " + error.getMessage());
            } finally {
                ThreadExecutor.leaveIO();
            }
        }

        return collInfo;
    }

    /**
     * Get the poster for a collection using the default language
     *
     * @param collectionId
     * @return
     */
    public String getCollectionPoster(int collectionId) {
        return getCollectionPoster(collectionId, languageCode);
    }

    /**
     * Get the poster for a collection
     *
     * @param collectionId
     * @param languageCode
     * @return
     */
    public String getCollectionPoster(int collectionId, String languageCode) {
        return getCollectionImage(collectionId, com.omertron.themoviedbapi.model.ArtworkType.POSTER, languageCode);
    }

    /**
     * Get the fanart for a collection using the default language
     *
     * @param collectionId
     * @return
     */
    public String getCollectionFanart(int collectionId) {
        return getCollectionFanart(collectionId, languageCode);
    }

    /**
     * Get the fanart for a collection
     *
     * @param collectionId
     * @param languageCode
     * @return
     */
    public String getCollectionFanart(int collectionId, String languageCode) {
        return getCollectionImage(collectionId, com.omertron.themoviedbapi.model.ArtworkType.BACKDROP, languageCode);
    }

    /**
     * Generic method to get the artwork for a collection.
     *
     * @param collectionId
     * @param artworkType
     * @param languageCode
     * @return
     */
    private String getCollectionImage(int collectionId, com.omertron.themoviedbapi.model.ArtworkType artworkType, String languageCode) {
        String returnUrl = Movie.UNKNOWN;
        String cacheKey = getCollectionImagesCacheKey(collectionId, languageCode);

        LOG.debug(LOG_MESSAGE + "Getting " + artworkType + " for collection ID " + collectionId + ", language '" + languageCode + "'");

        ArrayList<Artwork> results = (ArrayList<Artwork>) CacheMemory.getFromCache(cacheKey);

        if (results == null) {
            ThreadExecutor.enterIO(webhost);
            try {
                // Pass the language as null so that we get all images returned, even those without a language.
                TmdbResultsList<Artwork> collResults = TMDb.getCollectionImages(collectionId, null);

                if (collResults != null && collResults.getResults() != null && !collResults.getResults().isEmpty()) {
                    results = new ArrayList<Artwork>(collResults.getResults());
                    // Add to the cache
                    CacheMemory.addToCache(cacheKey, results);
                } else {
                    LOG.debug(LOG_MESSAGE + "No results found for " + collectionId + "-" + languageCode);
                }
            } catch (Exception error) {
                LOG.warn(LOG_MESSAGE + "Error getting CollectionImages: " + error.getMessage());
            } finally {
                ThreadExecutor.leaveIO();
            }
        }

        // Check we got some results
        if (results != null && !results.isEmpty()) {
            // Loop over the results looking for the required artwork type
            for (Artwork artwork : results) {
                if (artwork.getArtworkType() == artworkType && (StringUtils.isBlank(artwork.getLanguage()) || artwork.getLanguage().equalsIgnoreCase(languageCode))) {
                    try {
                        // We have a match, update the URL with the full path
                        URL url = TMDb.createImageUrl(artwork.getFilePath(), "original");
                        returnUrl = url.toString();

                        // Stop processing now.
                        break;
                    } catch (MovieDbException ex) {
                        LOG.warn(LOG_MESSAGE + "Failed to get URL for " + artworkType + ", error: " + ex.getMessage(), ex);
                    }
                }
            }
        }

        return returnUrl;
    }

    /**
     * Get a cache key for the collection
     *
     * @param collectionId
     * @return
     */
    public static String getCollectionCacheKey(int collectionId, String languageCode) {
        return getCacheKey(CACHE_COLLECTION, Integer.toString(collectionId), languageCode);
    }

    /**
     * Get a cache key for the collection images
     *
     * @param collectionId
     * @param languageCode
     * @return
     */
    public static String getCollectionImagesCacheKey(int collectionId, String languageCode) {
        return getCacheKey(CACHE_COLLECTION_IMAGES, Integer.toString(collectionId), languageCode);
    }

    /**
     * Get a cache key
     *
     * @param collectionId
     * @return
     */
    private static String getCacheKey(String cacheType, String collectionId, String languageCode) {
        return CacheMemory.generateCacheKey(cacheType, collectionId, languageCode);
    }
}

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
package com.moviejukebox.model;

import com.moviejukebox.tools.PropertiesUtil;
import java.util.ArrayList;
import java.util.List;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class LibraryTest {

    private static final Logger LOG = Logger.getLogger(LibraryTest.class);
    Library lib;
    final List<Movie> movies = new ArrayList<Movie>();

    @BeforeClass
    public static void setUpClass() {
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void setUp() {
        // TODO Artem: Decouple library from config.
        PropertiesUtil.setPropertiesStreamName("./properties/moviejukebox-default.properties");
        lib = new Library();
        lib.clear();

        movies.add(tv("The Sopranos", 1));
        movies.add(tv("The Sopranos", 2));
        movies.add(tv("The Sopranos", 3));
        movies.add(tv("The Sopranos", 4));
        movies.add(tv("M*A*S*H", 1));
        movies.add(tv("M*A*S*H", 2));
        movies.add(movie("Shrek"));
        movies.add(tv("Star Trek", 3));
        movies.add(tv("Star Trek", 7));
        movies.add(tv("Star Trek", 55));
        movies.add(movie("Shrek 2"));
        movies.add(tv("Doctor Who", 345));

        for (Movie movie : movies) {
            lib.addMovie(movie);
        }
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testIndex() {
        Index index = new Index();
        assertEquals(0, index.size());
        add10Movies(index);
        assertEquals(10, index.size());
        index.clear();
        assertEquals(0, index.size());
        index.setMaxCategories(5);
        add10Movies(index);
        assertEquals(5, index.size());
    }

    @Test
    public void testIndexByTVShowSeasons() {
        Library.setSingleSeriesPage(true);
        Index index = Library.indexBySets(movies);
        assertEquals(4, index.size());
        assertTrue(index.containsKey("The Sopranos"));
        assertTrue(index.containsKey("Star Trek"));
        assertTrue(index.containsKey("M*A*S*H"));
        assertEquals(4, index.get("The Sopranos").size());
    }

    private void add10Movies(Index index) {
        for (int i = 0; i < 10; i++) {
            index.addMovie("i" + i, new Movie());
        }
    }

    private static Movie tv(String title, int season) {
        Movie movie = new Movie();
        movie.setTitle(title, Movie.UNKNOWN);
        MovieFile mf = new MovieFile();
        mf.setSeason(season);
        mf.setFirstPart(1);
        movie.addMovieFile(mf);
        return movie;
    }

    private static Movie movie(String title) {
        return tv(title, -1);
    }
}

#Beam scenario generator
## Data sources
To generate new scenario for Beam we need to provide the following information:
1. household.csv and people.csv as an output of SynthPop. How to run it: https://github.com/LBNL-UCB-STI/synthpop#how-to-run-it
2. Census Transportation Planning Products Program data ([CTPP](https://ctpp.transportation.org/2012-2016-5-year-ctpp/)), link to FTP: ftp://data5.ctpp.transportation.org/
3. Shape files for:
    1. Traffic Analysis Zone (TAZ) shape file for specific state from Census Bureau: https://www2.census.gov/geo/tiger/TIGER2010/TAZ/2010/
    2. Block Group file for specific state from Census Bureau: https://www2.census.gov/geo/tiger/TIGER2019/BG/
4. Congestion level data for the area in CSV format
5. Conditional work duration (can be created using [NHTS data](https://nhts.ornl.gov/))
6. OSM PB map of area
7. US state code

![Data source](data_sources.svg)

## Scenario generator
[SynthPop](https://github.com/LBNL-UCB-STI/synthpop) generates households on **Block Group geo unit**, CTPP does not provide data on that level, the minimum level is **TAZ geo unit**.

Standard Hierarchy of Census Geographic Entities is from https://www.census.gov/newsroom/blogs/random-samplings/2014/07/understanding-geographic-relationships-counties-places-tracts-and-more.html):
 
![Standard Hierarchy of Census Geographic Entities](hierarchy_of_cencus_geo_entities.jpg). As we can see TAZs and Block Groups are on different scale. If we look to the shape files in [QGIS](https://qgis.org/en/site/), we will see the the cases when Block Group is bigger than TAZ and vise-versa. This issue is addressed in the way that we create a map from Block Group to all TAZs by using intersection of their geometries. 

### Flow chart
![Flow chart](flowchart.svg)
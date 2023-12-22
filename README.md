## Project Details

### Project Name
Project Odin: Real-Time Wildfire Monitoring and Response System

### Project Description
Project ODIN is a sophisticated real-time monitoring system designed to detect, analyze, and respond to wildfires. Leveraging emergency call inputs, and environmental sensors, it integrates this information into a central database, where it is continuously updated and made available to predictive modeling tools, which help forecast the spread and potential impact of the fires.

### Team Members and Contributions
Sofia Vaca Narvaja: Implemented and refined the FireVoiceImportActor for operational use with mocked data from a mocked  FlaskAPI that outputs coordinates and metadata to feed into CloudFireActor. Also set up and configured the CloudFireActor to interact with the GeoJSON data. Mocked another FlaskAPI
Mason Lee: Collaborated on the FireVoiceImportActor, ensuring its readiness. Helped develop the WildFireDataAvailable and FireTextImportActor for efficient data handling and played a key role in the CloudFireActor and FireVoiceActor implementation.
Sekai Tully Carr: Contributed to the implementation of the WildfireDataAvailable and FireTextImportActor, and developed the FireVoiceServiceActor configuration for seamless data display on the user interface.
Kaan Kilinc: Focused on configuration files for the FireVoiceImport Actor and developed mock classes for Cloudfire perimeter data. Develped the ui_cesium_firevoice component for effective data visualization. Worked on the README.
Peter Madoff: Worked on CloudFire and FireVoiceService actor classes and created mocked data for front-end testing. Worked to create unit testing. Completed the JavaScript connectivity for the FireVoiceService Actor, enabling user access to FireVoice data on the front-end.



### Total Estimated Time to Complete the Project
80 Hours

### Link to Repo
https://github.com/cs0320-f23/term-project-svacanar-kkilinc-pmadoff-mlee168-utullyca.git


## Design Choices
The Project ODIN system is architected with modular components to ensure a clear separation of concerns, promoting maintainability and scalability. Central to this system is the Actor System, which processes incoming JSON data. This system employs a hashmap to map different event types to specific processing functions, distinguishing between various data inputs such as emergency calls to ensure accurate data categorization and processing.

For the storage and retrieval of data, the system utilizes a Text DataBase that acts as a dynamic repository. Hashmaps are used extensively within this database to simulate server interactions and optimize data retrieval. This design choice negates the need for complex SQL queries, allowing for faster responses and lower latency.

Outputs, particularly the visualization of wildfire data, are managed by the UI-Cesium-Fire-Voice component. It leverages the robust Cesium library to render geospatial data in an interactive 3D interface. HTML tables are utilized where structured and readable outputs are necessary, providing end-users with an organized display of information.

The overarching components like the Master RACE Compiler and the system's front-end orchestrate the initialization of shared states and enforce access control among different components. This ensures that the integrity of data and state management is maintained throughout the application. The use of WebSockets for real-time data communication is a design choice that allows the system to push updates efficiently to the user interface, ensuring that users receive timely information during wildfire events.

By adopting this modular design and using specific data structures such as hashmaps and HTML tables, Project ODIN is equipped to handle the demands of real-time data processing and visualization, while maintaining system performance and reliability.

### Relationship Between Classes/Interfaces

Text Data Collector:
It is a data ingestion module, a Flask API that collects data from emergency 911 calls.
It writes the collected data to a dataset in the form of unstructured JSON files.
The collector populates the Text DataBase with JSON data, where the data is organized by timestamps and includes various content types and internal tags.
Text DataBase:
This database acts as a central repository that stores JSON data collected by the Text Data Collector.
It is queried by the Text/ImportActor to retrieve data, which is then sent to ODIN.
Text/ImportActor:
This class/interface is responsible for the retrieval of data from the Text DataBase at specific intervals.
After processing, it sends the JSON data into ODIN.
FireVoiceImportActor:
This actor connects to a Python API for geolocation, receiving JSON data that includes fire locations.
It processes data using a FireVoice API and then pulls mocked JSON data using a mocked API for testing purposes.
It then sends the geolocation data (coordinates + metadata) into ODIN.
FireVoice API:
This API is responsible for the geolocation algorithm that processes fire data.
It creates a dataset that is used by the FireVoiceImportActor.
Utilizes HuggingFace open-source tools to parse unstructured text into relevant wildfire location data.
CloudFireActor:
This actor interacts with a cloud-based simulation API to retrieve data about wildfire perimeters.
Sends complete fire data, including coordinates and metadata, to the Fire-Perim Data Base.
Fire-Perim Data Base:
It stores data regarding wildfire perimeters and is populated by the CloudFireActor.
This database is read by the FireVoice Layer HashMap to serve data to the front-end.
FireVoiceServiceActor:
This is the lead actor that takes in data from the other actors and sends it to the front-end.
It uses a websocket connection to send data from ODIN in the form of URLs that are rendered based on the actual file locations stored in a hashmap.
Websockets are used in order to ensure that the FireVoiceServiceActor and the frontend can communicate quickly and efficiently.
UI-Cesium-Fire-Voice:
This front-end module uses JavaScript with the Cesium geospatial library to render the FireVoice data.
It receives data updates via websockets and makes HTTP requests to the predefined service (managed by the FireVoiceServiceActor) for fetching data.
Master RACE Compiler:
Handles the dynamic generation of HTML and the rendering of the website.
Aggregates single-page JavaScript modules and manages user-event handling.


### Data Structures Used

Hash Maps: Employed in various modules of the system, enabling quick access to data and functions.
Function Mapping: Hashmaps map command names to their corresponding functions in the Actor System. This ensures that user commands are efficiently interpreted and the correct processing functions are executed without delay.
Data Retrieval: In the FireVoice Layer HashMap, hashmaps are used to associate data identifiers with their respective data storage locations. This allows the system to quickly retrieve the necessary data for processing or display without having to search through the entire dataset.
Mock Data Storage: To simulate server interactions, hashmaps store mocked data within the system. This facilitates the development and testing phases by providing quick responses and enabling performance optimization before deployment.

GeoJSON: For encoding geographical data related to wildfire events.
Geolocation Processing: The FireVoice API and CloudFire API use GeoJSON to represent and process the geospatial data acquired from various sources. This includes the locations of wildfires, their perimeters, and other relevant geographic features.
Data Visualization: GeoJSON data is rendered on the front-end using the UI-Cesium-Fire-Voice module. This allows for the creation of interactive maps that display real-time wildfire data in a user-friendly manner.



## Errors/Bugs
No errors or bugs.
### Checkstyle Errors
No checkstyle errors.


### Run our program
To run our program type the following line into the sbt shell terminal:
- run config/cesium/cesium-firevoice-proto.conf
This will launch the actor system and send data to be displayed to the cesium front-end.

### Test our program
Integration testing is performed using logging on the front end which can be reviewed through the chrom web dev xml trees. 
The back end can be tested using a specific testing files.


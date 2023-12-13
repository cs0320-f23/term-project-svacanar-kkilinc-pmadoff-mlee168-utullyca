"""
FireVoice Emergency Call Processor
===================================

This Python script creates a web server using Flask to process emergency calls.

How to Run:
-----------
1. Ensure all required libraries are installed: Flask, os, json, signal, and any other required libraries.
2. Configure the 'config.json' file with appropriate values:
    - OPENAI_API_KEY: API Key for OpenAI.
    - GOOGLE_MAPS_API_KEY: API Key for Google Maps.
    - DISPATCH_LAT: Latitude for dispatch location.
    - DISPATCH_LNG: Longitude for dispatch location.
3. Run the script: `python script_name.py`
4. The server will start on port 5000. You can make requests to the provided endpoints.

Endpoints:
----------
1. `/process`:
    - GET: Confirms that the server is running. Returns HTTP 200.
    - POST: Takes a 'filepath' form parameter, processes the emergency call, and returns incident details as JSON.

2. `/stopServer`:
    - GET: Shuts down the server.

Logs:
-----
All logs are written to 'firevoice.log' and also printed to the console.

"""


from flask import Flask, request, jsonify, abort, redirect, send_file, Response
from firevoice import FireVoice
import logging
import os
import json
import signal


logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s %(levelname)s %(module)s %(funcName)s %(message)s',
                    handlers=[logging.StreamHandler(), logging.FileHandler('firevoice.log')]
                    )
# Read the configuration from the JSON file
with open("config.json", "r") as f:
    config = json.load(f)

# Extract the variables from the configuration
OPENAI_API_KEY = config.get('OPENAI_API_KEY')
GOOGLE_MAPS_API_KEY = config.get('GOOGLE_MAPS_API_KEY')
DISPATCH_LAT = config.get('DISPATCH_LAT')  # Assuming lat/lng are floats
DISPATCH_LNG = config.get('DISPATCH_LNG')


# Validate that all needed configuration variables are set
if any(var is None for var in [OPENAI_API_KEY, GOOGLE_MAPS_API_KEY, DISPATCH_LAT, DISPATCH_LNG]):
    raise EnvironmentError("Some required configuration variables are not set")

logging.info("Configuration variables loaded successfully")


app = Flask(__name__)

# Initialize FireVoice instance
fv = FireVoice(OPENAI_API_KEY, GOOGLE_MAPS_API_KEY, DISPATCH_LAT, DISPATCH_LNG)

@app.route('/process', methods=["POST", "GET"])
def process_call():
    """
    Function to process emergency calls.

    If GET request is received:
    - Expected Output: HTTP 200 response to confirm server is running.

    If POST request is received:
    - Expected Input: Form parameter 'filepath' indicating the path to the audio file.
    - Processes the audio file through the FireVoice system.
    - Expected Output: JSON response containing incident details, HTTP 200 status if successful.
    - Errors:
        - HTTP 400 if 'filepath' is not provided or any other bad request occurs.
        - HTTP 500 if processing fails or if there is an internal server error.
    """
    start_time = logging.info("Start time of process_call()")
    try:
        if request.method == 'GET':
            logging.info("Test GET Request (Validation of Server Running)")
            response = Response(status=200)
            return response
        filepath = request.form.get('filepath')

        if filepath is None:
            logging.error("No filepath provided.")
            abort(400, description="Missing filepath.")

        logging.info(f"Processing call with filepath: {filepath}")

        call_id = fv.master_process_call(filepath)

        if call_id is None:
            logging.error(f"Processing failed. Could not retrieve call_id for {filepath}.")
            abort(500, description="Processing failed. Missing call ID.")

        incident_json = fv.retrieve_incident(call_id)

        if incident_json is None:
            logging.error(f"Processing failed. Could not retrieve incident data for call_id: {call_id}.")
            abort(500, description="Processing failed. Missing incident data.")

        logging.info(f"Successfully processed call for filepath: {filepath}")
        return jsonify(incident_json), 200

    except ValueError as ve:
        logging.error(f"Value error occurred: {ve}")
        abort(400, description=f"Bad request: {ve}")

    except KeyError as ke:
        logging.error(f"Missing key: {ke}")
        abort(400, description=f"Bad request: {ke}")

    except Exception as e:
        logging.error(f"An unexpected error occurred: {e}")
        abort(500, description="An internal error occurred.")

@app.route('/stopServer', methods=['GET'])
def stopServer():
    """
    Function to shut down the server.

    - Expected Output: JSON response {"success": True, "message": "Server has been shut down"} and HTTP 200 status.
    - Additionally, the server should initiate a shutdown sequence.
    """
    logging.info("Server shutdown initiated")
    os.kill(os.getpid(), signal.SIGINT)
    # return jsonify({"success": True, "message": "Server has been shut down"})


if __name__ == "__main__":
    logging.info("Loading server configurations and starting server...")
    # Add any initial setup or configurations here, if necessary
    logging.info("Server is ready")
    app.run(host="0.0.0.0", port=5000)

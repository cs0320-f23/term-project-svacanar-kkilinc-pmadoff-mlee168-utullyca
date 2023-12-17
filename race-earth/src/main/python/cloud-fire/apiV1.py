from flask import Flask, jsonify, request
import requests
import logging
import os
from queue import Queue
from threading import Semaphore, Thread, Lock
import time

# Configuration Management: Use environment variables or a configuration management system for these values.
API_URL = "http://firestarter.cloudfire.ai:8000"  # API URL
TIMEOUT = 120  # HTTP request timeout (seconds)
CONCURRENT_LIMIT = 6  # Maximum concurrent requests

# Initialize Flask app and configure logging
app = Flask(__name__)
log_file = 'real_cf_api.log'
if not os.path.exists(log_file):
    with open(log_file, 'w') as f:
        pass
logging.basicConfig(filename=log_file, level=logging.INFO, format='%(asctime)s:%(levelname)s:%(message)s')

# Create Semaphore for concurrent access control
semaphore = Semaphore(CONCURRENT_LIMIT)
request_queue = Queue()

# Dictionary to store the responses
responses = {}
responses_lock = Lock()  # Lock to synchronize access to the responses dictionary


def process_api_request(lat, lon, request_id):
    """Process API request and handle errors gracefully."""
    logging.info(f"Processing API request with ID: {request_id}, Latitude: {lat}, Longitude: {lon}")
    api_url = f"{API_URL}/?lon={lon}&lat={lat}"
    start_time = time.time()  # Record start time

    try:
        logging.info(f"Making HTTP request to: {api_url}")
        response = requests.get(api_url, timeout=TIMEOUT)
        end_time = time.time()  # Record end time

        duration = end_time - start_time  # Calculate duration
        logging.info(f"HTTP request to {api_url} completed in {duration:.2f} seconds with status code: {response.status_code}")

        with responses_lock:
            if response.status_code == 200:
                responses[request_id] = (True, response.json())
                logging.info(f"Response for request ID {request_id} stored successfully.")
            else:
                responses[request_id] = (False, {"error": "Failed to fetch data from API", "status_code": response.status_code})
                logging.error(f"Error in fetching data from API for request ID {request_id}. Status code: {response.status_code}")
    except requests.exceptions.Timeout as e:
        end_time = time.time()  # Record end time
        duration = end_time - start_time  # Calculate duration
        logging.error(f"HTTP request timeout for request ID {request_id} after {duration:.2f} seconds: {e}")
        with responses_lock:
            responses[request_id] = (False, {"error": "HTTP request timeout", "exception": str(e)})
    except IncompleteRead as e:
        logging.error(f"Incomplete read for request ID {request_id}: {e}")
        with responses_lock:
            responses[request_id] = (False, {"error": "HTTP request incomplete read", "exception": str(e)})
    except requests.RequestException as e:
        end_time = time.time()  # Record end time
        duration = end_time - start_time  # Calculate duration
        logging.error(f"HTTP request exception for request ID {request_id} after {duration:.2f} seconds: {e}")
        with responses_lock:
            responses[request_id] = (False, {"error": "HTTP request failed", "exception": str(e)})
    finally:
        semaphore.release()
        logging.info(f"Semaphore released for request ID {request_id}")


def handle_queued_requests():
    """Handle requests from the queue."""
    logging.info("Queue processor thread started.")
    while True:
        lat, lon, request_id = request_queue.get()
        logging.info(f"Request ID {request_id} retrieved from queue for processing.")
        process_api_request(lat, lon, request_id)
        request_queue.task_done()
        logging.info(f"Request ID {request_id} processing completed and marked as done in queue.")


# Start a thread to process queued requests
queue_processor_thread = Thread(target=handle_queued_requests, daemon=True)
queue_processor_thread.start()
logging.info("Queue processor thread initialized.")


@app.route('/geolocate_fire', methods=['GET'])
def geolocate_fire():
    """Handle geolocation requests."""
    request_lat = request.args.get('lat')
    request_lon = request.args.get('lon')
    request_id = str(time.time())  # Unique ID for each request
    logging.info(f"Received geolocate_fire request with Latitude: {request_lat}, Longitude: {request_lon}, Request ID: {request_id}")

    if not request_lat or not request_lon:
        logging.error("Missing latitude or longitude parameter in request.")
        return jsonify({"error": "Missing latitude or longitude parameter"}), 400
    try:
        request_lat = round(float(request_lat), 1)
        request_lon = round(float(request_lon), 1)
        logging.info(f"Parameters rounded for request ID {request_id}: Latitude: {request_lat}, Longitude: {request_lon}")
    except ValueError:
        logging.error("Invalid latitude or longitude value.")
        return jsonify({"error": "Invalid latitude or longitude value"}), 400

    if semaphore.acquire(blocking=False):
        logging.info(f"Semaphore acquired for immediate processing of request ID {request_id}")
        Thread(target=process_api_request, args=(request_lat, request_lon, request_id), daemon=True).start()
    else:
        logging.info(f"Request ID {request_id} added to queue due to semaphore limit.")
        request_queue.put((request_lat, request_lon, request_id))

    # Wait for the response to be available
    while True:
        with responses_lock:
            if request_id in responses:
                success, response_data = responses.pop(request_id)
                logging.info(f"Response for request ID {request_id} retrieved.")
                break
        time.sleep(0.1)  # Avoid busy waiting

    if success:
        logging.info(f"SUCCESS: Sending response for request ID {request_id}")
        return jsonify(response_data)
    else:
        logging.error(f"ERROR: Sending response for request ID {request_id}")
        return jsonify(response_data), 500


@app.route('/check_available', methods=['GET'])
def check_available():
    """Handle check available requests."""
    logging.info('Check available request received')
    return jsonify({"success": True, "message": "Service is available"})


@app.route('/stopServer', methods=['GET'])
def stop_server():
    """Handle server shutdown requests."""
    logging.info('Server shutdown request received')
    return jsonify({"success": True, "message": "Server has been shut down"})


if __name__ == "__main__":
    # Additional detailed logging for debugging
    logging.info('Application starting...')
    logging.info(f'Concurrent limit set to {CONCURRENT_LIMIT}')
    logging.info(f'Log file: {log_file}')
    logging.info(f'Responses dictionary initialized: {responses}')
    logging.info(f'Responses lock initialized: {responses_lock}')
    logging.info('Application started.')

    app.run(debug=True, port=5002)

from flask import Flask, jsonify, request, Response
import requests  # Import requests for making HTTP requests
import logging
import os

log_file = 'real_cf_api.log'
if not os.path.exists(log_file):
    with open(log_file, 'w') as f:
        pass

app = Flask(__name__)

logging.basicConfig(filename=log_file, level=logging.INFO, format='%(asctime)s:%(levelname)s:%(message)s')

@app.route('/check_available', methods=['GET'])
def check_available():
    logging.info('Check available request received')
    return jsonify({"success": True, "message": "Service is available"})

@app.route('/geolocate_fire', methods=['GET'])
def process_call():
    try:
        logging.info('Received GET request with data: ' + str(request.args))

        request_lat = request.args.get('lat')
        request_lon = request.args.get('lon')

        if request_lat is None or request_lon is None:
            logging.error('Latitude or longitude parameter is missing')
            return jsonify({"error": "Missing latitude or longitude parameter"}), 400

        try:
            request_lat = float(request_lat)
            request_lon = float(request_lon)
        except ValueError as e:
            logging.error(f"Invalid latitude or longitude value: {e}")
            return jsonify({"error": "Invalid latitude or longitude value"}), 400

        api_url = f"http://firestarter.cloudfire.ai:8000/geolocate_fire?lat={request_lat}&lon={request_lon}"
        logging.info(f"Making HTTP request to: {api_url}")

        # Define a function to make the HTTP request
        def fetch_data():
            return requests.get(api_url)

        # Use ThreadPoolExecutor to run the request asynchronously
        with concurrent.futures.ThreadPoolExecutor() as executor:
            future = executor.submit(fetch_data)
            try:
                # Set a timeout for the request
                response = future.result(timeout=10)  # 10 seconds timeout
            except concurrent.futures.TimeoutError:
                logging.error("HTTP request timed out")
                return jsonify({"error": "Request timed out"}), 504

        if response.status_code != 200:
            logging.error(f"Failed to fetch data from API. Status code: {response.status_code}")
            return jsonify({"error": "Failed to fetch data from API"}), response.status_code

        logging.info('Successfully fetched data from API')
        return jsonify(response.json())

    except requests.RequestException as e:
        logging.error(f"HTTP request failed: {e}")
        return jsonify({"error": "Internal server error"}), 500
    except Exception as e:
        logging.error(f"Error processing request: {e}")
        return jsonify({"error": "Internal server error"}), 500

@app.route('/stopServer', methods=['GET'])
def stop_server():
    logging.info('Server shutdown request received')
    return jsonify({"success": True, "message": "Server has been shut down"})

if __name__ == "__main__":
    app.run(debug=True, port=5001)

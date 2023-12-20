import openai
import time
import json
import re
from datetime import datetime
import os
import time
import json
import queue
import traceback  # Import the traceback module
import random
import string
import logging


# Mocked data representing fake 911 calls
mocked_data = [
    "Urgent report of a wildfire near 123 Forest Road",
    "Wildfire spotted at 456 Mountain Avenue, spreading rapidly",
    "Emergency at 789 Lakeview Drive due to a nearby wildfire",
    # Add more mocked data as needed
]
# Continuing to extend the Mocked data

mocked_data.extend([
    "Pine Valley electrical substation at risk of catching fire; coordinates 38.1234, -123.4567. Emergency fire control measures needed.",
    "Rescue team needed for a group of campers stranded in Pine Valley forest, near the old mill. Fast-moving wildfire reported in their vicinity.",
    "Urgent: Water bombing helicopter assistance requested over Pine Valley region to contain wildfire spread towards residential areas.",
    "Report from Pine Valley ranger station: fire has jumped containment line near the south ridge. Additional resources required.",
    "Emergency broadcast: All residents in Pine Valley's east sector advised to evacuate immediately due to wildfire threat.",
    "Non-fire related call: Medical assistance required at 1616 Willow Lane, Pine Valley, for a person experiencing a heart attack.",
    "Final update: Large section of Pine Valley National Park now under threat from wildfire. All hiking trails are closed, and evacuation is underway."
])
# Extending the Mocked data with more detailed entries

mocked_data.extend([
    "Multiple reports of a fast-moving wildfire approaching the outskirts of Pine Valley. Immediate assistance required.",
    "Resident at 1010 Cedar Lane reports seeing thick smoke rising from the nearby forest. Urgent evaluation needed.",
    "Hiker trapped on the north trail of Pine Valley Park, surrounded by flames. Coordinates are approximately 37.7749, -122.4194.",
    "Firefighters requested at 1313 Maple Street, Pine Valley, for evacuation assistance. Elderly residents need help.",
    "Wildlife sanctuary on Pine Valley outskirts reporting endangered species in danger due to approaching fire. Immediate intervention required.",
    "Local school at 1414 Oak Avenue, Pine Valley, requesting information on evacuation procedures due to nearby wildfire threat.",
    "Gas station explosion reported at 1515 Birch Road, Pine Valley, following the wildfire's spread to nearby fuel storage."
])

import random
random.shuffle(mocked_data)




#########################################################
## Flask Functions
def initialize_fireVoice(config):
    global client, assistantWildfireClassifier, assistantFireVoice, assistantFireGeolocation, data_dir
    global incoming_messages_thread_id, wildfire_raw_messages_thread_id, wildfire_messages_thread_id

    client = openai.Client(api_key=config['openai_api_key'])
    data_dir = mocked_config['data_dir']

    # Create threads for incoming messages and wildfire messages
    incoming_messages_thread = client.beta.threads.create(metadata={"name": "Incoming Messages"})
    incoming_messages_thread_id = incoming_messages_thread.id
    logging.info(f"Created incoming messages thread: {incoming_messages_thread_id}")

    wildfire_raw_messages_thread = client.beta.threads.create(metadata={"name": "Wildfire Messages"})
    wildfire_raw_messages_thread_id = wildfire_raw_messages_thread.id
    logging.info(f"Created wildfire raw messages thread: {wildfire_raw_messages_thread_id}")

    wildfire_messages_thread = client.beta.threads.create(metadata={"name": "Wildfire Messages"})
    wildfire_messages_thread_id = wildfire_messages_thread.id
    logging.info(f"Created wildfire messages thread: {wildfire_messages_thread_id}")

    # Create Assistants
    assistantWildfireClassifier = client.beta.assistants.create(
        name="Wildfire Classifier",
        instructions=(
            "YOU MUST RETURN PROPERLY FOMRATTED JSON. Your primary task is to meticulously examine the latest message provided in the thread and determine if it pertains to a wildfire incident. While you should focus solely on the most recent message for classification, use the context provided by earlier messages in the thread for better accuracy. Strictly return a JSON object with the key 'is_related_to_wildfires' and a Boolean value indicating the presence of wildfire-related content. For clarity, if the message concerns a wildfire, your response should be {'is_related_to_wildfires': True}. Conversely, if the message is unrelated to wildfires, respond with {'is_related_to_wildfires': False}. Your analysis must be thorough, considering various aspects like location details, urgency, and any specific mention of 'fire', 'smoke', or 'emergency' in the context of wildfires. Avoid ambiguity in your classification and ensure high precision and accuracy in your response. DO NOT RETURN ANY OTHER TEXT OTHER THAN THE SPECIFIED JSON RESPONSE"
        ),
        model="gpt-3.5-turbo-1106",
        # tools=[
        #     {
        #         "type": "function",
        #         "function": {
        #             "description": "Structure the output of classification as JSON",
        #             "name": "structure_json",
        #             "parameters": {
        #                 "type": "object",
        #                 "properties": {
        #                     "is_related_to_wildfires": {
        #                         "type": "boolean"
        #                     }
        #                 },
        #                 "required": ["is_related_to_wildfires"]
        #             }
        #         }
        #     }
        # ]
    )


    assistantFireVoice = client.beta.assistants.create(
        name="FireVoice",
        instructions=(
            "Your role is to conduct a deep analysis of the most recent message within the thread, along with its context, to identify and categorize the specific wildfire incident it describes. Leverage the entire thread's historical data to understand the evolving narrative of the incident and accurately assign a unique 'Incident_ID' and 'Call_ID'. The response must be a concise JSON object containing only 'Incident_ID' and 'Call_ID'. For instance, if you determine a message pertains to a known incident, return something like {'Incident_ID': 'WFA_123', 'Call_ID': '1233'}. It's crucial that you accurately correlate the incident details with previous messages in the thread, ensuring consistency and relevance in your identification. Your evaluation should be meticulous, considering elements like geographical references, time stamps, and the nature of the emergency as described in the messages. DO NOT RETURN ANY OTHER TEXT OTHER THAN THE SPECIFIED JSON RESPONSE"
        ),
        model="gpt-3.5-turbo-1106"
    )


    assistantFireGeolocation = client.beta.assistants.create(
        name="Fire Geolocation",
        instructions=(
            "Your objective is to analyze the entire thread related to a wildfire incident, with a focus on the most recent message, to extract and return detailed geolocation information. Utilize all messages in the thread to piece together a comprehensive understanding of the wildfire's location and characteristics. Your output must be a well-defined JSON object including 'Coordinates', 'Address', 'Incident_Report', and 'Severity_Rating'. For example, if the latest message provides new or corroborative details about the location, your response should enhance the existing geolocation data, like {'Coordinates': [[34.0522, -118.2437]], 'Address': ['123 Santa Monica Ave', '432 Santa Monica Ave'], 'Incident_Report': 'Large wildfire spotted near Los Angeles', 'Severity_Rating': 'High'}. Your analysis should be detail-oriented, considering the incremental updates in the thread, and synthesizing information to present the most accurate and current geolocation data. DO NOT RETURN ANY OTHER TEXT OTHER THAN THE SPECIFIED JSON RESPONSE"
        ),
        model="gpt-3.5-turbo-1106"
    )

def process_incoming_message(incoming_message):
    """
    Processes an incoming message and returns the final payload.

    Args:
        incoming_message (str): The incoming message to process.

    Returns:
        dict: The final payload generated from the incoming message.
    """
    logging.info(f"#######################################################################")
    logging.info(f"Processing message: {incoming_message}")

    # Post the incoming message to the incoming messages thread
    message_id = post_message(incoming_messages_thread_id, incoming_message)
    logging.info(f"Posted message to incoming messages thread. Message ID: {message_id}")

    # Fetch the latest message from the incoming messages thread
    print_latest_message(incoming_messages_thread_id)

    # Check if the message is related to wildfires
    is_related = is_related_to_wildfires(incoming_messages_thread_id)
    logging.info(f"Message is related to wildfires: {is_related}")

    if is_related:
        logging.info(f"Processing wildfire-related message...")

        # Get Incident ID and Call ID
        incident_id, call_id = get_incident_id(wildfire_messages_thread_id)
        call_id = ensure_unique_call_id(incident_id, call_id)
        logging.info(f"Assistant Classification: Incident ID: {incident_id}, Call ID: {call_id}")

        # Get or create a thread for the specific incident
        incident_thread_id = get_or_create_incident_thread(incident_id)
        logging.info(f"Incident Thread ID: {incident_thread_id}")

        # Post the incoming message to the incident thread
        post_message(incident_thread_id, incoming_message)



        max_retries = 3
        for attempt in range(1, max_retries + 1):
            try:
                logging.info(f"%%%%%%%%%%% {attempt }%%%%%%%%%%%%")
                logging.info(f"Processing message: {incoming_message}")

                # Get geolocation data
                geolocation_data = geolocate_incident(incident_thread_id)
                logging.info(f"Geolocation Data: {geolocation_data}")
                # Check if geolocation_data is not None
                if geolocation_data is None:
                  raise ValueError(f"Geolocation data not found.")

                # Prepare the final output payload
                current_date = datetime.utcnow().strftime(f"%Y-%m-%dT%H:%M:%SZ")
                final_payload = {
                    "Call_ID": call_id,
                    "Coordinate_Type": "GPS",
                    "Coordinates": geolocation_data.get(f"Coordinates", []),
                    "Incident_ID": incident_id,
                    "Incident_Report": geolocation_data.get(f"Incident_Report", ""),
                    "Severity_Rating": geolocation_data.get(f"Severity_Rating", ""),
                    "date": current_date
                }
                logging.info(f"Final Payload: {json.dumps(final_payload, indent=2)}")
                save_payload(incident_id, call_id, final_payload)
                return final_payload

            except (json.JSONDecodeError, ValueError) as e:
                logging.info(f"Attempt {attempt} failed with error: {e}")
                if attempt == max_retries:
                    logging.info(f"Max retries reached. Unable to process the message.")
                    return None  # Or handle the failure as appropriate

        save_payload(incident_id, call_id, final_payload)
        return final_payload

    return {"status": "Not related to wildfires"}

def add_message_to_queue(message):
    """
    Adds a message to the processing queue.

    Args:
        message (dict): The message to be processed.
    """
    incoming_queue.put(message)
    logging.info(f"Message added to queue.")

def start_processing_loop():
    """
    Starts the main loop for processing incoming messages.
    """
    logging.info(f"Starting processing loop...")
    while True:
        if not incoming_queue.empty():
            incoming_message = incoming_queue.get()
            final_payload = process_incoming_message(incoming_message)
            # Handle the final payload (e.g., save it, send it to an API, etc.)
            logging.info(f"Processed payload: {final_payload}")
        else:
            logging.info(f"Queue is empty. Waiting for messages...")
            time.sleep(5)  # Adjust sleep time as needed
#####################################################3
# Function to run an assistant in JSON mode
def run_assistant_json(assistant_id, thread_id):
    response = client.beta.threads.runs.create(
        thread_id=thread_id,
        assistant_id=assistant_id
    )

    # Wait for the run to complete
    while True:
        run = client.beta.threads.runs.retrieve(thread_id=thread_id, run_id=response.id)
        #logging.info(run)
        if run.status == "completed":
            logging.info(f"Run completed {assistant_id} and thread {thread_id}")
            break
        else:
            logging.info(f"run_assistant_json {assistant_id} and thread {thread_id}: In progress...")
            time.sleep(5)

    # Retrieve the latest message from the thread
    latest_message = get_latest_message_from_thread(thread_id)
    if latest_message:
        # Extract message string
        raw_string = latest_message.content[0].text.value
        # Ensure the string is in valid JSON format
        # Parse the JSON string
        response_dict = process_json(raw_string)
        return response_dict



#######################################################################################
## Parser:
def process_json(raw_string):
    """
    Processes a string to convert it into a JSON object.

    Args:
        raw_string (str): The raw string to be processed.

    Returns:
        dict: A dictionary representing the JSON object, or None if parsing fails.
    """
    def extract_json_string(raw_string):
        # Look for JSON markers
        json_start = raw_string.find(f"```json")
        json_end = raw_string.find(f"```", json_start + 7)

        if json_start != -1 and json_end != -1:
            raw_string = raw_string[json_start + 7:json_end].strip()

        # Fallback to regex extraction if markers are not found
        pattern = r'\{.*?\}'  # Non-greedy match for content within curly braces
        matches = re.findall(pattern, raw_string, re.DOTALL)
        if matches:
            return max(matches, key=len)  # Return the longest match

        return None

    def escape_inner_quotes(string):

        return re.sub(r'(?<!\\)"(?=.*?":)', r'\"', string)

    def fix_quotes(string):
        # Replace single quotes with double quotes
        return string.replace(f"'", '"')

    def correct_comma_misplacements(string):
        # Correcting misplaced commas before closing braces or brackets
        string = re.sub(r',\s*([}\]])', r'\1', string)
        # Correcting misplaced commas after opening braces or brackets
        return re.sub(r'([{\[])\s*,', r'\1', string)

    def normalize_data_formats(string):
        # Example: Normalizing date formats (this can be expanded based on specific needs)
        string = re.sub(r'(\d{2})/(\d{2})/(\d{4})', r'\3-\2-\1', string)
        # Add more normalization rules as needed
        return string

    def fix_boolean_values(string):
        # Replace 'True' with 'true' and 'False' with 'false'
        return string.replace(f"True", "true").replace(f"False", "false")

    def remove_trailing_commas(string):
        # Remove trailing commas in JSON objects or arrays
        string = re.sub(r',\s*}', '}', string)
        return re.sub(r',\s*\]', ']', string)

    def fix_unquoted_keys(string):
        # Add double quotes around unquoted keys
        return re.sub(r'([{,]\s*)(\w+)(\s*:)', r'\1"\2"\3', string)

    def fix_unmatched_quotes(string):
        """Fixes unmatched single quotes in a string."""
        stack = []
        new_string = ''
        for char in string:
            if char == "'" and (not stack or stack[-1] != "'"):
                stack.append(char)
            elif char == "'" and stack[-1] == "'":
                stack.pop()
            new_string += char
        return new_string

    def unescape_escaped_characters(string):
        return string.replace(r'\"', '"')


    logging.info(f"Original String: {raw_string}")

    json_string = extract_json_string(raw_string)
    if not json_string:
        logging.info(f"No JSON content found in the string.")
        return None

    formatted_string = json_string

    # Apply each fix in a try-except block
    for fix_function in [fix_quotes, fix_boolean_values, remove_trailing_commas,
                          fix_unquoted_keys,
                          correct_comma_misplacements, unescape_escaped_characters,
                          ]:
        try:
            formatted_string = fix_function(formatted_string)
        except Exception as e:
            logging.info(f"Error in {fix_function.__name__}: {e}")
    try:
        # Parse the JSON string
        response_dict = json.loads(formatted_string)
        return response_dict

    except Exception as e:
        # logging.info the error message and full traceback
        traceback.print_exc()
        logging.info(f"Error parsing JSON string: {e}")
        logging.info(f"Error JSON string: {formatted_string}")

        return None
#######################################################################################
# Function to simulate wildfire text classification
# Will run on a single thread of all incoming messages (only one message that is contiunually altered)
def is_related_to_wildfires(thread_id):

    # no text input because it's already added to the thread
    response = run_assistant_json(assistantWildfireClassifier.id, thread_id)
    return response.get(f"is_related_to_wildfires", False)

# Function to simulate getting incident ID from the text
# Will run on a single thread of all wildfire classified data
def get_incident_id(thread_id):
    response = run_assistant_json(assistantFireVoice.id, thread_id)
    return response.get(f"Incident_ID"), response.get(f"Call_ID")


# Function to geolocate the coordinates / addressed from all data within a certain thread of Incident ID
# The messages should be pushed to the thread before this is called
# Multiple Threads (one for each Incident ID)
def geolocate_incident(thread_id):
    response = run_assistant_json(assistantFireGeolocation.id, thread_id)
    return response

#########################################################################################
# Function to create a new thread for an incident and add it to the global list
def create_thread(incident_id):
    thread = client.beta.threads.create(metadata={"Incident_ID": incident_id})
    thread_id = thread.id
    global_incident_thread_ids.append(thread_id)  # Add to global list
    return thread_id

# Function to retrieve a thread by incident ID
# Updated function to retrieve a thread by incident ID from the global list
def retrieve_thread_by_incident_id(incident_id):
    for thread_id in global_incident_thread_ids:
        # Retrieve thread details
        my_thread = client.beta.threads.retrieve(thread_id)
        if my_thread.metadata.get('Incident_ID') == incident_id:
            return thread_id
    return None

# Modified function to get or create an incident thread
def get_or_create_incident_thread(incident_id):
    # Check if a thread for the incident already exists
    thread_id = retrieve_thread_by_incident_id(incident_id)
    if thread_id is None:
        # If not, create a new thread for the incident
        thread_id = create_thread(incident_id)
    return thread_id

# Function to post a message to a specific thread
def post_message(thread_id, message, role="user"):
    # Logic to post a message to the specified thread
    message = client.beta.threads.messages.create(
        thread_id=thread_id,
        role=role,
        content=message
    )
    return message.id
# Function to update a specific incident thread with new data
def update_incident_thread(thread_id, data):
    # Logic to update the incident thread with new data
    # This could involve posting a new message to the thread with the updated data
    post_message(thread_id, json.dumps(data), role="assistant")

# Function to get the latest message from a thread
def get_latest_message_from_thread(thread_id):
    messages = client.beta.threads.messages.list(thread_id=thread_id, order='desc', limit=1)
    logging.info(messages)
    if messages.data:
        latest_message = messages.data[0]
        # Extract the text content from the message
        return latest_message
    return None


###########################
### Printing Debugging Functions

def print_thread_details(thread_id, print_messages=True, print_runs=True):
    """
    Prints all messages and runs for a given thread ID.

    Args:
    - thread_id (str): The ID of the thread.
    - print_messages (bool): If True, prints messages of the thread.
    - print_runs (bool): If True, prints runs of the thread.
    """
    if print_messages:
        messages = client.beta.threads.messages.list(thread_id=thread_id)
        logging.info(f"Messages in Thread:", json.dumps(messages, indent=2))

    if print_runs:
        runs = client.beta.threads.runs.list(thread_id=thread_id)
        logging.info(f"Runs in Thread:", json.dumps(runs, indent=2))

def extract_message_string(message):
  return message.content[0].text.value

def print_latest_message(thread_id):
    """
    Prints the latest message from a specified thread.

    Args:
    - thread_id (str): The ID of the thread.
    """
    messages = client.beta.threads.messages.list(thread_id=thread_id, order='desc', limit=1)
    if messages.data:
        latest_message = messages.data[0]
        logging.info(f"Latest Message: {latest_message}")

        latest_message_value = latest_message.content[0].text.value
        logging.info(f"Latest Message Value: {latest_message_value}")
    else:
        logging.info(f"No messages found in the thread.")

def print_run_status(thread_id, run_id):
    """
    Prints the status of a specified run in a thread.

    Args:
    - thread_id (str): The ID of the thread containing the run.
    - run_id (str): The ID of the run.
    """
    run = client.beta.threads.runs.retrieve(thread_id=thread_id, run_id=run_id)
    logging.info(f"Run Status:", json.dumps(run, indent=2))

def get_all_text_in_thread(thread_id):
    """
    Retrieves all text from the messages in a specified thread.

    Args:
        thread_id (str): The ID of the thread.

    Returns:
        str: Concatenated text of all messages in the thread.
    """
    all_text = ""
    try:
        # Fetch all messages from the thread
        messages = client.beta.threads.messages.list(thread_id=thread_id)
        for message in messages.data:
            # Extract the text content from each message
            text_content = message.content[0].text.value
            all_text += text_content + "\n"  # Add a newline for separation between messages
    except Exception as e:
        logging.info(f"Error fetching messages from thread {thread_id}: {e}")
    return all_text

def delete_all_threads():
    client = OpenAI()

    # Function to list all threads
    def list_all_threads():
        threads = []
        last_id = None

        while True:
            response = client.beta.threads.list(after=last_id)
            threads.extend(response['data'])

            if not response['has_more']:
                break

            last_id = response['data'][-1]['id']

        return threads

    # Function to delete a thread
    def delete_thread(thread_id):
        try:
            client.beta.threads.delete(thread_id)
            logging.info(f"Deleted thread {thread_id}")
        except Exception as e:
            logging.info(f"Error deleting thread {thread_id}: {e}")

    # List and delete all threads
    all_threads = list_all_threads()
    for thread in all_threads:
        delete_thread(thread['id'])


# New function to handle file system operations
def save_payload(incident_id, call_id, payload):
    # Ensure the data directory for the incident exists
    incident_dir = os.path.join(data_dir, incident_id)
    if not os.path.exists(incident_dir):
        os.makedirs(incident_dir)

    # Save the payload to a file
    payload_filename = os.path.join(incident_dir, f"{call_id}.json")
    with open(payload_filename, 'w') as file:
        json.dump(payload, file, indent=2)
    logging.info(f"Payload saved to {payload_filename}")


def generate_random_call_id():
    """Generate a random call ID with 3 uppercase letters followed by 3 digits."""
    letters = ''.join(random.choices(string.ascii_uppercase, k=3))
    digits = ''.join(random.choices(string.digits, k=3))
    return letters + digits

def ensure_unique_call_id(incident_id, call_id):
    """
    Ensure that the call ID is unique for the given incident.
    If not unique, generate a new random call ID.

    Args:
        incident_id (str): The incident ID.
        call_id (str): The call ID to be verified for uniqueness.

    Returns:
        str: A unique call ID for the incident.
    """
    global incident_call_ids

    # Initialize the call ID list for the incident if not already present
    if incident_id not in incident_call_ids:
        incident_call_ids[incident_id] = []

    # Check if call_id is unique and generate a new one if needed
    while call_id in incident_call_ids[incident_id]:
        call_id = generate_random_call_id()

    # Add the unique call_id to the incident's call ID list
    incident_call_ids[incident_id].append(call_id)

    return call_id
def print_all_text_in_thread(thread_id):
    """
    Prints all text from the messages in a specified thread.

    Args:
        thread_id (str): The ID of the thread.
    """
    logging.info('!!!!!!!!!!!!!!!!!!!!!!!!')
    logging.info(f'!! Current Thread Data: {thread_id}')
    logging.info(get_all_text_in_thread(thread_id))
    logging.info('!!!!!!!!!!!!!!!!!!!!!!!!')

############################
import queue
from threading import Thread


# Function to simulate adding messages to the queue every 5 seconds
def add_mocked_data_to_queue():
    for data in mocked_data:
        incoming_queue.put(data)
        time.sleep(5)  # Wait for 5 seconds before adding the next message


# Main loop to continuously process incoming messages
def main_processing_loop():
    logging.info(f"Starting main processing loop...")
    initialize_fireVoice(mocked_config)  # Initialize necessary threads and assistants

    while True:
        logging.info(f"Checking the incoming queue...")
        if not incoming_queue.empty():
            # Process each incoming message
            incoming_message = incoming_queue.get()
            final_payload = process_incoming_message(incoming_message)
            logging.info(f"Outputting final payload...")
            # Handle final payload (e.g., saving it, sending it to an API, etc.)
        else:
            logging.info('Empty Queue. Waiting...')
            time.sleep(10)  # Adjust the sleep time as needed

# Background thread to process incoming messages
def process_messages():
    while True:
        if not incoming_queue.empty():
            incoming_message = incoming_queue.get()
            final_payload = process_incoming_message(incoming_message)
            # Further actions with final_payload can be implemented here
        else:
            time.sleep(1)  # Adjust sleep time as needed


# Define the path to the log file
log_file = 'race-earth/src/main/python/fire-voice/fv_api.log'

# Check if the log file exists
if os.path.exists(log_file):
    # Clear the contents of the existing log file by opening it in write mode
    with open(log_file, 'w'):
        pass  # This will clear the file

# Verify the existence of the log file
if os.path.exists(log_file):
    print(f"The log file '{log_file}' exists.")
else:
    print(f"The log file '{log_file}' does not exist.")
    
# Configure the logger
logging.basicConfig(filename=log_file, level=logging.INFO, format='%(asctime)s:%(levelname)s:%(message)s')



# Start the main processing loop
#main_processing_loop()
########################################
from flask import Flask, jsonify, request, Response
import threading
import queue
import logging


app = Flask(__name__)
incoming_queue = queue.Queue()

# # Initialize FireVoice once at startup
# @app.before_first_request
# def initialize():
#     initialize_fireVoice(mocked_config)

# Function to process and respond to a call
@app.route('/process', methods=['GET', 'POST'])
def process_call():
    if request.method == 'GET':
        logging.info('Received test GET request')
        return Response(status=200)

    try:
        incoming_message = request.json.get('message')
        if incoming_message:
            # Process the message to generate final_payload
            final_payload = process_incoming_message(incoming_message)
            logging.info(f"Final payload: {final_payload}")
            # Return the final_payload as the response
            return jsonify(final_payload)
        else:
            # Handle the case where no message is provided
            raise ValueError("No message provided")
    except Exception as e:
        # Handle any exceptions or errors that occur during processing
        error_message = str(e)
        logging.error(f"Error during processing: {error_message}")
        return jsonify({"error": error_message}), 500

@app.route('/stopServer', methods=['GET'])
def shutdown():
    shutdown_server()
    return jsonify({"success": True, "message": "Fv-Prod Server has been shut down"})

def shutdown_server():
    func = request.environ.get('werkzeug.server.shutdown')
    if func is None:
        raise RuntimeError('Not running with the Werkzeug Server')
    func()

def run_server():
    app.run(port=5007, debug=True)


# Global variables
incident_call_ids = {}
global_incident_thread_ids = []
client = None
assistantWildfireClassifier = None
assistantFireVoice = None
assistantFireGeolocation = None


# Mocked config data as a Python dictionary
mocked_config = {
    "openai_api_key": "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",
    "data_dir": "race-earth/src/main/python/fire-voice/fv-data"
}

# Queue to hold the incoming messages
incoming_queue = queue.Queue()
# Start the thread to add mocked data to the queue
#Thread(target=add_mocked_data_to_queue, daemon=True).start() ## Uncomment this to use default mocked data


if __name__ == "__main__":
    # Start the background thread for processing messages
    processing_thread = threading.Thread(target=process_messages, daemon=True)
    processing_thread.start()
    initialize_fireVoice(mocked_config)

    # Start the Flask server
    logging.info(f"Flask server started.")
    print('starting fireVoice API')
    run_server()

## conda install --file C:\Users\mason\Desktop\test\ODIN-Fire-Lee\race-earth\src\main\python\fire-voice\requirements.txt
## conda create --name fv-env-prod --file C:\Users\mason\Desktop\test\ODIN-Fire-Lee\race-earth\src\main\python\fire-voice\requirements.txt

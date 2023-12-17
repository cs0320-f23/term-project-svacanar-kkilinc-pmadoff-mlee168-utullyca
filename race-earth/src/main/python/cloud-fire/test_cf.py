import unittest
import requests
import threading
import time

class APITest(unittest.TestCase):
    BASE_URL = "http://localhost:5002"
    GEOLOCATE_ENDPOINT = "/geolocate_fire"
    CHECK_AVAILABLE_ENDPOINT = "/check_available"

    def test_check_available(self):
        """Test the /check_available endpoint."""
        print("Testing /check_available endpoint...")
        response = requests.get(self.BASE_URL + self.CHECK_AVAILABLE_ENDPOINT)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()['success'])
        print("  /check_available test completed.")

    def test_geolocate_fire(self):
        """Test the /geolocate_fire endpoint with single request."""
        print("Testing /geolocate_fire endpoint with a single request...")
        params = {"lat": "33.6", "lon": "-117.4"}
        response = requests.get(self.BASE_URL + self.GEOLOCATE_ENDPOINT, params=params)
        self.assertIn(response.status_code, [200, 500])  # Assuming these are valid responses
        print("  /geolocate_fire test completed.")

    def test_consecutive_requests(self):
        """Test handling of multiple consecutive requests."""
        print("Testing handling of multiple consecutive requests...")
        def make_request():
            params = {"lat": "33.6", "lon": "-117.4"}
            response = requests.get(self.BASE_URL + self.GEOLOCATE_ENDPOINT, params=params)
            self.assertIn(response.status_code, [200, 500])

        threads = []
        for _ in range(10):  # Number of consecutive requests
            thread = threading.Thread(target=make_request)
            thread.start()
            threads.append(thread)

        for thread in threads:
            thread.join()
        print("  Handling of multiple consecutive requests test completed.")
    def test_wrong_endpoint_get(self):
        """Test sending a GET request to a non-existent endpoint."""
        print("Testing sending a GET request to a non-existent endpoint...")
        wrong_endpoint = "/wrong_endpoint"
        response = requests.get(self.BASE_URL + wrong_endpoint)
        self.assertEqual(response.status_code, 404)  # Expecting a 404 response code
        print("  GET request to a wrong endpoint test completed.")

    def test_wrong_endpoint_post(self):
        """Test sending a POST request to a non-existent endpoint."""
        print("Testing sending a POST request to a non-existent endpoint...")
        wrong_endpoint = "/wrong_endpoint"
        response = requests.post(self.BASE_URL + wrong_endpoint)
        self.assertEqual(response.status_code, 404)  # Expecting a 404 response code
        print("  POST request to a wrong endpoint test completed.")

    def test_wrong_method_endpoint(self):
        """Test sending a request with an incorrect HTTP method to an existing endpoint."""
        print("Testing sending a request with an incorrect HTTP method to an existing endpoint...")
        response = requests.post(self.BASE_URL + self.CHECK_AVAILABLE_ENDPOINT)
        self.assertEqual(response.status_code, 405)  # Expecting a 405 response code (Method Not Allowed)
        print("  Incorrect HTTP method test completed.")

    def test_empty_endpoint(self):
        """Test sending a request with an empty endpoint."""
        print("Testing sending a request with an empty endpoint...")
        response = requests.get(self.BASE_URL)
        self.assertEqual(response.status_code, 404)  # Expecting a 404 response code
        print("  Empty endpoint test completed.")

    def test_malformed_endpoint(self):
        """Test sending a request with a malformed endpoint."""
        print("Testing sending a request with a malformed endpoint...")
        malformed_endpoint = "/malformed/endpoint"
        response = requests.get(self.BASE_URL + malformed_endpoint)
        self.assertEqual(response.status_code, 404)  # Expecting a 404 response code
        print("  Malformed endpoint test completed.")
if __name__ == '__main__':
    unittest.main()

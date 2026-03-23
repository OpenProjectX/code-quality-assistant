Feature: Comments API

  Background:
    * url 'https://jsonplaceholder.typicode.com'
    * configure connectTimeout = 5000
    * configure readTimeout = 10000

  Scenario: Get all comments
    Given path '/comments'
    When method GET
    Then status 200
    And match response == '#array'
    And match each response == { id: '#number', postId: '#number', name: '#string', email: '#string', body: '#string' }
    * assert response.length > 0

  Scenario: Get all comments — content-type header
    Given path '/comments'
    When method GET
    Then status 200
    And match responseHeaders['Content-Type'][0] contains 'application/json'

  Scenario: Get comments filtered by postId=1
    Given path '/comments'
    And param postId = 1
    When method GET
    Then status 200
    And match response == '#array'
    And match each response == { id: '#number', postId: '#number', name: '#string', email: '#string', body: '#string' }
    And match each response contains { postId: 1 }

  Scenario: Get comments filtered by unknown postId
    Given path '/comments'
    And param postId = 99999
    When method GET
    Then status 200
    And match response == []

  Scenario: Get a comment by ID
    Given path '/comments/1'
    When method GET
    Then status 200
    And match response == { id: 1, postId: '#number', name: '#string', email: '#string', body: '#string' }

  Scenario: Get a comment by ID — content-type header
    Given path '/comments/1'
    When method GET
    Then status 200
    And match responseHeaders['Content-Type'][0] contains 'application/json'

  Scenario: Get a comment — non-existent ID
    Given path '/comments/999999'
    When method GET
    Then status 404

  Scenario: Get a comment — invalid ID type
    Given path '/comments/abc'
    When method GET
    Then status 404

  Scenario: Create a comment
    Given path '/comments'
    And request { postId: 1, name: 'Test Comment Name', email: 'test@example.com', body: 'Test comment body content' }
    When method POST
    Then status 201
    And match response == { id: '#number', postId: 1, name: 'Test Comment Name', email: 'test@example.com', body: 'Test comment body content' }

  Scenario: Create a comment — content-type header
    Given path '/comments'
    And request { postId: 1, name: 'Test Comment Name', email: 'test@example.com', body: 'Test comment body content' }
    When method POST
    Then status 201
    And match responseHeaders['Content-Type'][0] contains 'application/json'

  Scenario: Create a comment — missing required fields
    Given path '/comments'
    And request {}
    When method POST
    Then status 400

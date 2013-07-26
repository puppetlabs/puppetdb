/**
 * PuppetDB Dashboard javascript
 *
 * @author Brian Cain
 */

/**
 *
 * Begins the process to get a query when the button is pressed
 */

function getQuery() {
  var query = document.getElementById('queryTxt'),
      endPoint = document.getElementById('endpointDropdown'),
      version = document.getElementById('versionList'),
      url = "";

  document.getElementById("results").innerHTML = "";
  document.getElementById("demo").innerHTML = "";
  document.getElementById("tableResults").innerHTML = "";

  if (version.value === '') {
    console.error("ERROR: no version selected");
    document.getElementById("results").innerHTML = "<font color=\"red\">**An error has occured. No version selected.**</font>";
    return;
  }
  else if (endPoint.value === '') {
    console.error("ERROR: no query endpoint selected");
    document.getElementById("results").innerHTML = "<font color=\"red\">**An error has occured. No endpoint selected.**</font>";
    return;
  }

  // construct url
  url += "/" + version.value + "/";

  if (endPoint.value === 'fact-names') {
    url += endPoint.value;
  }
  else {
    url += endPoint.value + "?" + $.param({query: query.value});
  }

  document.getElementById("demo").innerHTML="HTTP GET " + url;

  //printResults(url, endPoint);
  setCookie(query, version, endPoint);
  tableResults(url, endPoint, version);
  getCookie();
}

/**
 *
 * Function to save cookie of previous queries
 *
 * Hopeful workflow:
 *  - Get cookie
 *  - Append new query to value if not exist
 *  - Then set cookie
 */

function setCookie(query, version, endPoint) {
  var $cookieValue = $.cookie('my_cookie'),
      queryCookie = version.value + "\t" + endPoint.value + "\t" + query.value.replace(/\s+/g, '');

  if ($cookieValue === undefined) {
    $cookieValue = queryCookie + "\n";
  }
  else if (isExistingQuery($cookieValue, queryCookie)) {
    return;
  }
  else {
    $cookieValue += queryCookie + "\n";
  }
  $.cookie('my_cookie', $cookieValue);
}

/**
 *
 * Function to get cookie of previous queries
 */

function getCookie() {
  var $cookie = $.cookie('my_cookie');
  // console.log($cookie);
  return $cookie;
}

/**
 *
 * Function to delete a given cookie
 */

function deleteCookie(name){
  var $isDeletedCookie = $.removeCookie(name);
  if ($isDeletedCookie) {
    console.log("Cookie " + name + " was found and deleted.");
  }
  else {
    console.error("Cookie " + name + " was not found.");
  }
}

/**
 *
 * Checks to see if the user has already entered a query
 *
 * If not, return false so it can be saved into the cookie
 *
 * If yes, return true so it won't be duplicated
 */

function isExistingQuery(cookie, new_query){
  var queryArr = cookie.split(/\n/);

  for (var query in queryArr) {
    if (queryArr[query] === new_query){
      return true;
    }
  }

  return false;
}

/**
 *
 * Function that builds a list of links of previous queries stored in the cookie
 */

function buildPrevQueries(){
  var $cookieVal = getCookie(),
      queryHTML = "",
      count = 0,
      vers,
      endpoint,
      $cookieArr,
      splitQuery;

  if ($cookieVal === undefined) {
    $('#prevQueries').html("<p>No Previous Queries</p>");
  }
  else {
    $cookieArr = $cookieVal.split(/\n/);
    $cookieArr = $cookieArr.reverse();
    queryHTML += "<dl><dt>Previous Queries</dt><dd>";
    for (var query in $cookieArr) {
      if ($cookieArr[query] !== '') {
        if (count < 10) {
          splitQuery = $cookieArr[query].split(/\t/);
          vers = getMenuNumber(splitQuery[0]);
          endpoint = getMenuNumber(splitQuery[1]);
          userQuery = splitQuery[2];
          queryHTML += "<li><code><a href=\"#\" onclick=\"dynamicLoad('" + vers + "', '" + endpoint + "', '" + escape(userQuery) + "')\">" + $cookieArr[query] + "</a></code></li>";
          count += 1;
        }
      }
    }
    queryHTML += "</dd></dl>";
    $('#prevQueries').html(queryHTML);
  }
}

/**
 *
 * Convert version and endpoint to number that it relates to in menu
 */

function getMenuNumber(menuVar){
  if (menuVar === 'v2'){
    return 1;
  }
  else if(menuVar === 'experimental') {
    return 2;
  }
  else if (menuVar === 'facts') {
    return 1;
  }
  else if (menuVar === 'resources') {
    return 2;
  }
  else if (menuVar === 'nodes') {
    return 3;
  }
  else if (menuVar === 'reports') {
    return 1;
  }
  else if (menuVar === 'events') {
    return 2;
  }
}

/**
 *
 * Returns the key,value pairs of available APIs from PuppetDB
 *
 * Eventually it would be nice if this function queried puppetDB
 *  and got this information from an HTTP GET json
 */

function getEndPointsMap() {
  var endPointsMap = {};
  endPointsMap['v2'] = ['facts','resources','nodes','fact-names','metrics'];
  endPointsMap['experimental'] = ['reports','events'];

  /*console.log(endPointsMap);

  var keys = [];

  for (var key in endPointsMap){
    if (endPointsMap.hasOwnProperty(key)){
      console.log("Key: " + key);
      console.log("Value: " + endPointsMap[key]);
      for (var val in endPointsMap[key]){
        console.log(endPointsMap[key][val]);
      }
    }
  }*/

  return endPointsMap;
}

/**
 *
 * Builds endpoint dropdowns
 */

function buildVersionDropdown() {
  var endPointsMap = getEndPointsMap(),
      endPointTable = "<select id=\"endpointDropdown\" onchange=\"isTextboxVisible()\">",
      version = document.getElementById('versionList'),
      dropdownVisible,
      endPointArray;

  endPointTable += "<option></option>";

  document.getElementById("epDropdown").innerHTML = "";

  if (endPointsMap.hasOwnProperty(version.value)) {
    endPointArray = endPointsMap[version.value];
    for (var val in endPointArray) {
      endPointTable += "<option>" + endPointArray[val] + "</option>";
    }
  }

  endPointTable += "</select>";
  $("#epDropdown").append(endPointTable);
  isDropdownVisible();
}

/**
 *
 * Build puppetdb version dropdowns
 */

function buildDropdowns() {
  var endPointsMap = getEndPointsMap();
  var versionTable = "<select id=\"versionList\" onchange=\"buildVersionDropdown()\">";

  versionTable += "<option></option>";
  for (var key in endPointsMap) {
    if(endPointsMap.hasOwnProperty(key)) {
      versionTable += "<option>" + key + "</option>";
    }
  }
  versionTable += "</select>";

  $("#versionDropdown").append(versionTable);
}

/**
 *
 * Construct html to draw table
 */

function tableResults(url, endPoint, versionNumber) {
  var table = "",
      count = 0,
      head = "",
      body = "",
      formattedElement = "";

  /*if (endPoint.value === 'fact-names' || versionNumber.value === 'v1' && (endPoint.value === 'nodes' || endPoint.value === 'status')) {
    $.getJSON(url,function(result) {
      table = "<table class=\"table table-hover\">";
      head = "<thead><tr><th>#</th><th>Fact Name</th></tr></thead>";
      body = "<tbody>";
      $.each(result, function(i, field) {
        body += "<tr><td>" + i + "</td>" + "<td>" + field + "</td></tr>";
      });
      body += "</tbody>";
      table += head;
      table += body;
      table += "</table>";
    })
    .fail(function() {
        console.error("An error has occured") 
        $("#results").html("<font color=\"red\">**An error has occured See the console for details.**</font>");
    } )
    .done(function() {
      $("#tableResults").append(table);
    } );
  }*/
  $.getJSON(url,function(result) {
    table = "<table class=\"table table-hover\">";
    $.each(result, function(i, field) {
      if (count === 0) {
        head = "<thead><tr>";
      }
      body = "<tbody><tr>";
      $.each(field, function(i, elem) {
        formattedElement = formatElement(elem);
        if (count === 0) {
          head += "<th>" + i + "</th>";
        }
        body += "<td>" + formattedElement + "</td>";
      });
      if (count === 0) {
        head += "</tr></thead>";
      }
      body += "</tr></tbody>";
      if (count === 0) {
        table += head;
        count = 1;
      }
      table += body
    });
    table += "</table>";
  })
  .fail(function() {
      console.error("An error has occured") 
      $("#results").html("<font color=\"red\">**An error has occured See the console for details.**</font>");
  } )
  .done(function() {
    $("#tableResults").append(table);
  } );
}

function formatElement(elem) {
  if (elem === null) {
    return "";
  }
  else {
    return elem;
  }
}

/**
 *
 * Dropdown visibility and other functions
 */

function isTextboxVisible() {
  var option = document.getElementById('endpointDropdown');
  if (option.value === 'fact-names' || option.value === 'metrics' || option.value === '') {
    document.getElementById('queryTxt').disabled = true;
    document.getElementById('queryTxt').placeholder = "";
  }
  else {
    document.getElementById('queryTxt').disabled = false;
    generatePlaceholder();
  }
}

function isDropdownVisible() {
  var option = document.getElementById('versionList');
  if (option.value === '') {
    document.getElementById('endpointDropdown').disabled = true;
    document.getElementById('queryTxt').disabled = true;
  }
  else {
    document.getElementById('endpointDropdown').disabled = false;
  }
  isTextboxVisible();
}

function generatePlaceholder() {
  var version = document.getElementById('versionList'),
      endPointVal = document.getElementById('endpointDropdown');

  document.getElementById('queryTxt').placeholder = "[\"<OPERATOR>\", \"<FIELD>\", \"<VALUE>\"]";
}


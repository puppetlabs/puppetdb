/**
 * PuppetDB Query Dashboard javascript
 *
 * @author Brian Cain
 */

/**************************************************
 * BEGIN Constants
 *************************************************/

var COOKIE_NAME = 'puppetdb-query-cookie';
var END_POINTS_MAP = {};
END_POINTS_MAP['v2'] = ['facts','resources','nodes','fact-names','metrics'];
END_POINTS_MAP['experimental'] = ['reports','events'];

/**************************************************
 * END Constants
 *************************************************/

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
  var $cookieValue = $.cookie(COOKIE_NAME),
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
  $.cookie(COOKIE_NAME, $cookieValue);
}

/**
 *
 * Function to get cookie of previous queries
 */

function getCookie() {
  var $cookie = $.cookie(COOKIE_NAME);
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
 *
 * Queries are shown from newest used query to oldest
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
          vers = splitQuery[0];
          endpoint = splitQuery[1];
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
    var keyCount = 1,
        valResult = 0;

  for (var key in END_POINTS_MAP) {
    if (menuVar === key) {
      return keyCount;
    }
    else {
      keyCount += 1;
    }
    for (var val in END_POINTS_MAP[key]) {
      if (menuVar === END_POINTS_MAP[key][val]) {
        valResult = parseInt(val) + 1;
        return valResult;
      }
    }
  }
}

/**
 *
 * Builds endpoint dropdowns
 */

function buildVersionDropdown() {
  var endPointTable = "<select id=\"endpointDropdown\" onchange=\"updateTextboxVisibility()\">",
      version = document.getElementById('versionList'),
      dropdownVisible,
      endPointArray;

  endPointTable += "<option></option>";

  document.getElementById("epDropdownContainer").innerHTML = "";

  if (END_POINTS_MAP.hasOwnProperty(version.value)) {
    endPointArray = END_POINTS_MAP[version.value];
    for (var val in endPointArray) {
      endPointTable += "<option>" + endPointArray[val] + "</option>";
    }
  }

  endPointTable += "</select>";
  $("#epDropdownContainer").append(endPointTable);
  updateDropdownVisibility();
}

/**
 *
 * Build puppetdb version dropdowns
 */

function buildDropdowns() {
  var versionTable = "<select id=\"versionList\" onchange=\"buildVersionDropdown()\">";

  versionTable += "<option></option>";
  for (var key in END_POINTS_MAP) {
    if(END_POINTS_MAP.hasOwnProperty(key)) {
      versionTable += "<option>" + key + "</option>";
    }
  }
  versionTable += "</select>";

  $("#versionDropdownContainer").append(versionTable);
}

/**
 *
 * Construct html to draw table
 */

function tableResults(url, endPoint, versionNumber) {
  var table = "",
      body = "",
      head = "";
      formattedElement = "";

  $.getJSON(url,function(result) {
    table = "<table class=\"table table-hover\">";
    body = "<tbody><tr>";
    head = "<thead><tr>";

    for (var header in result[0]){
      head += "<th>" + header + "</th>";
    }

    head += "</tr></thead>";
    table += head;

    $.each(result, function(i, field) {
      $.each(field, function(i, elem) {
        formattedElement = formatElement(elem);
        body += "<td>" + formattedElement + "</td>";
      });
      body += "</tr></tbody>";
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

/**
 *
 * This function will be used to make
 * the tables look less "auto-generated".
 *
 * For now it only worries about if an element is null. If
 *  it's null, just return a blank.
 */

function formatElement(elem) {
  return elem || "";
}

/**
 *
 * Dropdown visibility and other functions
 */

function updateTextboxVisibility() {
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

function updateDropdownVisibility() {
  var option = document.getElementById('versionList');
  if (option.value === '') {
    document.getElementById('endpointDropdown').disabled = true;
    document.getElementById('queryTxt').disabled = true;
  }
  else {
    document.getElementById('endpointDropdown').disabled = false;
  }
  updateTextboxVisibility();
}

function generatePlaceholder() {
  var version = document.getElementById('versionList'),
      endPointVal = document.getElementById('endpointDropdown');

  document.getElementById('queryTxt').placeholder = "[\"<OPERATOR>\", \"<FIELD>\", \"<VALUE>\"]";
}


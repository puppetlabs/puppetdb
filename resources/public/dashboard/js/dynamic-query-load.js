/**
 * PuppetDB Dashboard javascript
 *
 * @author Brian Cain
 */

/**************************************************
 * BEGIN Constants
 *************************************************/


// Map of useful queries that are provided as examples for user
var QUERY_MAP = {};
QUERY_MAP['uptimeFact'] = ['["and", ["=", "name", "uptime_seconds"], [">=", "value", 100000], ["<", "value", 1000000]]'];
QUERY_MAP['factOS'] = ['["=", "name", "operatingsystem"]'];
QUERY_MAP['nodeKernelLinux'] = ['["=", ["fact", "kernel"], "Linux"]'];
QUERY_MAP['resourceCert'] = ['["=", "certname", "test.example.com"]'];
QUERY_MAP['resourceExport'] = ['["and", ["=", "certname", "test.example.com"], ["=", "exported", true]]'];
QUERY_MAP['factCert'] = ['["=", "certname", "test.example.com"]'];
QUERY_MAP['reportLoad'] = ['["=", "certname", "example.local"]'];
QUERY_MAP['eventLoad'] = ['["=", "certname", "example.local"]'];

/**************************************************
 * END Constants
 *************************************************/

/**
 *
 * Functions to dynamically load useful queries that
 *  have been saved as previous queries.
 */

function dynamicLoad(versionSelect, endpointSelect, queryText) {
  $('#queryTxt').val('');
  $('#versionList')[0].selectedIndex=getVersionDropdown(versionSelect);
  buildVersionDropdown();
  $('#endpointDropdown')[0].selectedIndex=getEndpointDropdown(endpointSelect);
  updateTextboxVisibility();
  //document.getElementById('queryTxt').innerHTML = unescape(queryText);
  $('#queryTxt').val(unescape(queryText));
}

/**
 *
 * Function used in HTML tags to dynamically load example queries
 */

function queryDynamicLoad(versionSelect, endpointSelect, key, startRange, endRange){
  $('#queryTxt').val('');
  $('#versionList')[0].selectedIndex=getVersionDropdown(versionSelect);
  buildVersionDropdown();
  $('#endpointDropdown')[0].selectedIndex=getEndpointDropdown(endpointSelect);
  updateTextboxVisibility();
  $('#queryTxt').val(QUERY_MAP[key]);
  $('#queryTxt')[0].focus();
  $('#queryTxt')[0].setSelectionRange(parseInt(startRange),parseInt(endRange));
}

/**
 *
 * Takes a string that relates to the PuppetDB Version
 *
 * Returns a number that relates to the position of the puppetdb version
 *  within the dropdown DOM.
 */

function getVersionDropdown(versionSelect) {
  var versionDOM = $('#versionList')[0];

  for (var elem in versionDOM) {
    if (versionSelect === versionDOM.options[elem].value) {
      return elem;
    }
  }
}

/**
 *
 * Takes a string that relates to the PuppetDB end point
 *
 * Returns a number that relates to the position of the 
 *   endpoint within the dropdown DOM
 */

function getEndpointDropdown(endpointSelect) {
  var endpointDOM = $('#endpointDropdown')[0];

  for (var elem in endpointDOM) {
    if (endpointSelect === endpointDOM.options[elem].value) {
      return elem;
    }
  }
}

/**
 * PuppetDB Dashboard javascript
 *
 * @author Brian Cain
 */

/**
 *
 * Functions to dynamically load useful queries
 */

function dynamicLoad(versionSelect, endpointSelect, queryText) {
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=versionSelect;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=endpointSelect;
  isTextboxVisible();
  //document.getElementById('queryTxt').innerHTML = unescape(queryText);
  $('#queryTxt').val(unescape(queryText));
}

/**
 *
 * Rest of the functions...
 */

function uptimeFactLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=1;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=1;
  isTextboxVisible();
  $('#queryTxt').val('["and", ["=", "name", "uptime_seconds"], [">=", "value", 100000], ["<", "value", 1000000]]');
}

function factOSLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=1;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=1;
  isTextboxVisible();
  $('#queryTxt').val('["=", "name", "operatingsystem"]');
}

function nodeKernelLinuxLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=1;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=3;
  isTextboxVisible();
  $('#queryTxt').val('["=", ["fact", "kernel"], "Linux"]');
}

function resourceCertLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=1;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=2;
  isTextboxVisible();
  $('#queryTxt').val('["=", "certname", "test.example.com"]');
  document.getElementById('queryTxt').focus();
  document.getElementById('queryTxt').setSelectionRange(19,35);
}

function resourceExportLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=1;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=2;
  isTextboxVisible();
  $('#queryTxt').val('["and", ["=", "certname", "test.example.com"], ["=", "exported", true]]');
  document.getElementById('queryTxt').focus();
  document.getElementById('queryTxt').setSelectionRange(27,43);
}

function factCertLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=1;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=1;
  isTextboxVisible();
  $('#queryTxt').val('["=", "certname", "test.example.com"]');
  document.getElementById('queryTxt').focus();
  document.getElementById('queryTxt').setSelectionRange(19,35);
}

function reportLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=2;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=1;
  isTextboxVisible();
  $('#queryTxt').val('["=", "certname", "example.local"]');
  document.getElementById('queryTxt').focus();
  document.getElementById('queryTxt').setSelectionRange(19,32);
}

function eventLoad(){
  $('#queryTxt').val('');
  document.getElementById('versionList').selectedIndex=2;
  buildVersionDropdown();
  document.getElementById('endpointDropdown').selectedIndex=2;
  isTextboxVisible();
  $('#queryTxt').val('["=", "certname", "example.local"]');
  document.getElementById('queryTxt').focus();
  document.getElementById('queryTxt').setSelectionRange(19,32);
}

// Utility actions for RESP API 

// Check
function WhoAmI( )
{
  print( vars.$lo_ufname);
  vars.$L_exit = "normal";
}

/*
 TODO: function checks supplied user for complying access restriction  all services  marked for asyc access and having restrictions
 TODO: names of the services accessible for the user adds to the list 
*/
function CheckCredentials()
{
   
  vars.$L_file.email="FOSpp, FOSeaist";
  vars.$L_exit = "normal";

}


function setThreadID( )
{
 return Date.now().toString(16)+":"+Math.floor(Math.random()*1000).toString(16);   
}

// Debug function 
// Add to login.auto
function NOP()
{
    vars.$lo_request_number ++;
    vars.$L_file.email= "Thread:"+vars.$lo_thread_id+" Seq: "+vars.$lo_request_number;  
    vars.$L_exit = "normal";
}
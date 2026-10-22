import {Component,computed,effect,inject,signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Api} from './api';
interface Column {name:string;dataType:string;byteLength:number|null;characterLength:number|null;}
@Component({standalone:true,imports:[FormsModule],template:`
<div class="page-heading"><div class="eyebrow">EDAC EXTRACTION</div><h1>Extraction Scripts</h1><p>Generate an Oracle script package for execution on your extraction server.</p></div>
<section class="panel"><label>Source database connection<select [ngModel]="api.selectedId()" (ngModelChange)="changeConnection($event)" [disabled]="api.busy()">@for(c of api.connections();track c.id){<option [ngValue]="c.id">{{c.name}} — {{c.databaseType}}</option>}</select></label>
@if(api.selected()?.databaseType!=='ORACLE'){<p role="status">Select an Oracle connection to generate extraction scripts.</p>}
<p>Server requirements: Bash, Oracle SQLcl and Python 3.8+. Saved passwords are never included.</p>
</section>
<section class="panel"><h2>Output settings</h2><div class="two-column">
<label>Output format<select [(ngModel)]="format" [disabled]="api.busy()"><option value="DELIMITED">Delimited</option><option value="FIXED">Fixed-Length</option></select></label>
<label>Parallel extraction jobs<input type="number" min="1" max="32" [(ngModel)]="parallelJobs" [disabled]="api.busy()"></label>
@if(format==='DELIMITED'){<label>Delimiter<select [(ngModel)]="delimiterChoice" [disabled]="api.busy()"><option value="BEL">BEL (existing interface)</option><option value="SPACE">Space</option><option value="TAB">Tab</option><option value="PIPE">Pipe |</option><option value="COMMA">Comma</option><option value="CUSTOM">Custom</option></select></label>@if(delimiterChoice==='CUSTOM'){<label>Custom delimiter<input [(ngModel)]="customDelimiter" maxlength="2" [disabled]="api.busy()"></label>}}
<label><input type="checkbox" [(ngModel)]="envelope" [disabled]="api.busy()">Include Header, Detail and Trailer records</label>
@if(envelope){<label>System code<input [(ngModel)]="systemCode" maxlength="20" [disabled]="api.busy()"></label><label>Country code<input [(ngModel)]="countryCode" maxlength="20" [disabled]="api.busy()"></label>}
</div>
@if(format==='DELIMITED'){<p class="note">Values containing the delimiter or double quotes are wrapped in double quotes. Embedded quotes are doubled. NULL is \\N. Line breaks in text become spaces.</p>}
@else{<p class="note">Widths are UTF-8 bytes. Text is padded with spaces; numbers with zeros after the sign. A value exceeding its width fails extraction instead of being truncated.</p>}
<p>The processing date is supplied as BUSINESS_DATE when running the scripts. This screen generates scripts; it does not run an extraction.</p>
</section>
<section class="panel"><h2>Tables from the table list</h2><label><input type="checkbox" [checked]="allSelected()" (change)="selectAll($any($event.target).checked)" [disabled]="api.busy()">Select all tables ({{selected().size}} / {{api.tables().length}})</label>
<div class="table-scroll" style="max-height:420px;overflow:auto"><table><thead><tr><th>Select</th><th>Schema</th><th>Table or view</th></tr></thead><tbody>@for(t of api.tables();track t.id){<tr><td><input type="checkbox" [attr.aria-label]="'Extract '+t.schema+'.'+t.name" [checked]="selected().has(t.id)" (change)="toggle(t.id,$any($event.target).checked)" [disabled]="api.busy()"></td><td>{{t.schema}}</td><td>{{t.name}}</td></tr>}</tbody></table></div>
@if(!api.tables().length){<p>Add tables or Oracle extraction views in Table list first.</p>}
<p>All tables are selected by default. Column exclusions saved in Mapping workbook are respected.</p></section>
<section class="panel"><h2>Per-table options</h2><label>Table options<select [ngModel]="optionTable" (ngModelChange)="loadColumns($event)" [disabled]="api.busy()"><option [ngValue]="null">Choose a table to configure</option>@for(t of chosenTables();track t.id){<option [ngValue]="t.id">{{t.schema}}.{{t.name}}</option>}</select></label>
@if(optionTable!==null){<label>Control sum column<select [(ngModel)]="sums[''+optionTable]" [disabled]="api.busy()"><option value="">None</option>@for(c of columns();track c.name){@if(numeric(c)){<option [value]="c.name">{{c.name}}</option>}}</select></label>
<div class="table-scroll" style="max-height:420px;overflow:auto"><table><thead><tr><th>Included column</th><th>Oracle type</th><th>Fixed width (bytes)</th></tr></thead><tbody>@for(c of columns();track c.name){<tr><td>{{c.name}}</td><td>{{c.dataType}}</td><td><input type="number" min="1" max="32767" [attr.aria-label]="'Width '+c.name" [(ngModel)]="widths[optionTable+':'+c.name]" [disabled]="api.busy()"></td></tr>}</tbody></table></div>}
<p>Metadata provides default widths. Review widths for production data. Control sums are optional and use a numeric included column.</p></section>
<section class="panel"><button class="button" (click)="generate()" [disabled]="api.busy()||!selected().size||api.selected()?.databaseType!=='ORACLE'">Generate script ZIP</button><p role="status">{{message()}}</p></section>
`})
export class ExtractionComponent {
 api=inject(Api);selected=signal(new Set<number>());columns=signal<Column[]>([]);message=signal('');optionTable:number|null=null;
 format='DELIMITED';delimiterChoice='BEL';customDelimiter=';';parallelJobs=15;envelope=true;systemCode='PBI';countryCode='SG';
 widths:Record<string,number>={};sums:Record<string,string>={};private current:number|null=null;private version=0;private known=new Set<number>();
 allSelected=computed(()=>this.api.tables().length>0&&this.selected().size===this.api.tables().length);
 chosenTables=computed(()=>this.api.tables().filter(t=>this.selected().has(t.id)));
 constructor(){effect(()=>{const connection=this.api.selectedId(),tables=this.api.tables();if(this.current!==connection){this.current=connection;this.known=new Set();this.widths={};this.sums={};this.optionTable=null;this.columns.set([]);this.message.set('');this.version++;}
 const available=new Set(tables.map(t=>t.id));const selection=new Set([...this.selected()].filter(id=>available.has(id)));for(const t of tables)if(!this.known.has(t.id))selection.add(t.id);this.known=available;
 if(selection.size!==this.selected().size||[...selection].some(id=>!this.selected().has(id)))this.selected.set(selection);
 });}
 changeConnection(id:number){void this.api.run(()=>this.api.select(id));}
 selectAll(value:boolean){this.selected.set(new Set(value?this.api.tables().map(t=>t.id):[]));this.clearOptions();}
 toggle(id:number,value:boolean){const next=new Set(this.selected());value?next.add(id):next.delete(id);this.selected.set(next);this.clearOptions();}
 clearOptions(){if(this.optionTable!==null&&!this.selected().has(this.optionTable)){this.optionTable=null;this.columns.set([]);this.version++;}}
 numeric(c:Column){return /^(NUMBER|NUMERIC|DECIMAL|INTEGER|INT|SMALLINT|FLOAT|BINARY_FLOAT|BINARY_DOUBLE|DOUBLE PRECISION|REAL)$/i.test(c.dataType);}
 defaultWidth(c:Column){return c.dataType==='DATE'?10:c.dataType.startsWith('TIMESTAMP')?(c.dataType.includes('TIME ZONE')?33:26):this.numeric(c)?80:Math.min(32767,Math.max(1,c.byteLength??3000));}
 loadColumns(id:number|null){this.optionTable=id;this.columns.set([]);const version=++this.version,connection=this.api.selectedId();if(id===null||connection===null)return;void this.api.run(async()=>{const columns=await this.api.get<Column[]>('/connections/'+connection+'/extraction-scripts/columns/'+id);if(version!==this.version||connection!==this.api.selectedId())return;this.columns.set(columns);this.sums[''+id]??='';for(const c of columns)this.widths[id+':'+c.name]??=this.defaultWidth(c);});}
 delimiter(){return ({BEL:'\u0007',SPACE:' ',TAB:'\t',PIPE:'|',COMMA:','} as Record<string,string>)[this.delimiterChoice]??this.customDelimiter;}
 generate(){const connection=this.api.selectedId();if(connection===null)return;void this.api.run(async()=>{this.message.set('');const ids=[...this.selected()],valid=new Set(ids.map(String));const widths=Object.fromEntries(Object.entries(this.widths).filter(([key])=>valid.has(key.split(':')[0]))),controlSumColumns=Object.fromEntries(Object.entries(this.sums).filter(([key])=>valid.has(key)));
 const blob=await this.api.downloadScripts(connection,{tableIds:ids,format:this.format,delimiter:this.delimiter(),parallelJobs:this.parallelJobs,envelope:this.envelope,systemCode:this.systemCode,countryCode:this.countryCode,widths,controlSumColumns});
 const url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='Oracle_Extraction_Scripts.zip';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);this.message.set('Script package generated for '+ids.length+' table(s). Configure the server connection and processing date before running.');});}
}
